package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import ir.ramtung.tinyme.domain.service.matcher.AuctionMatcher;
import ir.ramtung.tinyme.domain.service.matcher.ContinuousMatcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@Service
public class SecurityHandler {
    public MatchResult newOrder(Security security, EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) {
        var orderBook = security.getOrderBook();
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(security,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity())) {
            return MatchResult.notEnoughPositions();
        }
        Order order = getOrder(enterOrderRq, security, broker, shareholder);

        return switch (security.matchingState()) {
            case CONTINUOUS -> handleNewOrderByContinuousStrategy(security, order, enterOrderRq);
            case AUCTION -> handleNewOrderByAuctionStrategy(security, order, enterOrderRq);
        };
    }

    public void deleteOrder(Security security, DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        var orderBook = security.getOrderBook();
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public MatchResult updateOrder(Security security, EnterOrderRq updateOrderRq) throws InvalidRequestException {
        var orderBook = security.getOrderBook();
        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        validateUpdateOrderRequest(updateOrderRq, order);
        if (doesNotHaveEnoughPositions(security, updateOrderRq, order)) {
            return MatchResult.notEnoughPositions();
        }

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }

        return switch (security.matchingState()) {
            case CONTINUOUS -> handleUpdatedOrderByContinuousStrategy(security, updateOrderRq, order);
            case AUCTION -> handleUpdatedOrderByAuctionStrategy(security, updateOrderRq, order);
        };
    }

    public MatchResult executeAuction(Security security) {
        assert security.matchingState() == MatchingState.AUCTION;
        var auctionMatcher = (AuctionMatcher) security.getMatcher();
        var matchResult = auctionMatcher.execute(security);

        updateLastTransactionPrice(security, matchResult);

        var activatedOrders = tryActivateQueuedStopOrdersThenReturnTheActivated(security);
        activatedOrders.forEach(matchResult::addActivatedOrder);
        return matchResult;
    }

    private boolean doesLosePriority(EnterOrderRq updateOrderRq, Order originalOrder) {
        return originalOrder.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != originalOrder.getPrice()
                || ((originalOrder instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()))
                || ((originalOrder instanceof StopOrder stopOrder) && (
                (stopOrder.getSide() == Side.BUY && stopOrder.getStopPrice() > updateOrderRq.getStopPrice())
                        || (stopOrder.getSide() == Side.SELL && stopOrder.getStopPrice() < updateOrderRq.getStopPrice())));
    }

    private boolean doesNotHaveEnoughPositions(Security security, EnterOrderRq updateOrderRq, Order order) {
        var orderBook = security.getOrderBook();
        return updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(security,
                        orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity());
    }

    private void validateUpdateOrderRequest(EnterOrderRq updateOrderRq, Order order) throws InvalidRequestException {
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if ((order instanceof StopOrder) && order.isActive() && updateOrderRq.getStopPrice() != 0)
            throw new InvalidRequestException(Message.INVALID_STOP_PRICE);
        if (!(order instanceof StopOrder) && updateOrderRq.getStopPrice() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_A_NON_STOP_ORDER);
    }

    private Order getOrder(EnterOrderRq enterOrderRq, Security security, Broker broker, Shareholder shareholder) {
        if (enterOrderRq.getPeakSize() > 0)
            return new IcebergOrder(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize());
        if (enterOrderRq.getStopPrice() > 0) {
            return new StopOrder(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), enterOrderRq.getStopPrice());
        }
        return new Order(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime());
    }

    private void updateLastTransactionPrice(Security security, MatchResult result) {
        assert result != null;
        if (!result.trades().isEmpty())
            security.setLastTransactionPrice(result.trades().getLast().getPrice());
    }

    /**
     * @return true if activation happens and false otherwise
     */
    public boolean tryActivateStopOrder(Security security, StopOrder order) {
        var orderBook = security.getOrderBook();
        var lastTransactionPrice = orderBook.getLastTransactionPrice();

        if (lastTransactionPrice == null) return false;
        if (order.isActive()) return false;

        var condition = (order.getSide() == Side.BUY && order.getStopPrice() <= lastTransactionPrice)
                || (order.getSide() == Side.SELL && order.getStopPrice() >= lastTransactionPrice);
        if (condition) {
            order.activate();
            return true;
        }
        return false;
    }

    private List<Order> tryActivateQueuedStopOrdersThenReturnTheActivated(Security security) {
        var orderBook = security.getOrderBook();
        List<Order> activatedOrders = new LinkedList<>();
        for (Order order : orderBook.getBuyQueue()) {
            if (order instanceof StopOrder stopOrder
                    && tryActivateStopOrder(security, stopOrder)) {
                activatedOrders.add(stopOrder);
            }
        }
        return activatedOrders;
    }

    private MatchResult handleNewOrderByAuctionStrategy(Security security, Order order, EnterOrderRq enterOrderRq) {
        if (enterOrderRq.getMinimumExecutionQuantity() != 0) {
            return MatchResult.minimumQuantityConditionForAuctionMode();
        }
        return security.getMatcher().executeWithoutMatching(order);
    }

    private MatchResult handleNewOrderByContinuousStrategy(Security security, Order order, EnterOrderRq enterOrderRq) {
        var matcher = security.getMatcher();
        List<Order> activatedOrders = new ArrayList<>();
        if (order instanceof StopOrder stopOrder && tryActivateStopOrder(security, stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        var matchResult = ((ContinuousMatcher) matcher).executeWithMinimumQuantityCondition(order, enterOrderRq.getMinimumExecutionQuantity());

        updateLastTransactionPrice(security, matchResult);

        activatedOrders.addAll(tryActivateQueuedStopOrdersThenReturnTheActivated(security));
        activatedOrders.forEach(matchResult::addActivatedOrder);
        return matchResult;
    }

    private MatchResult handleUpdatedOrderByContinuousStrategy(Security security, EnterOrderRq updateOrderRq, Order order) {
        var matcher = security.getMatcher();
        var orderBook = security.getOrderBook();
        assert matcher instanceof ContinuousMatcher;
        var continuousMatcher = (ContinuousMatcher) matcher;

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!doesLosePriority(updateOrderRq, originalOrder)) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        }

        order.markAsNew();
        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        List<Order> activatedOrders = new ArrayList<>();
        if (order instanceof StopOrder stopOrder && tryActivateStopOrder(security, stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        MatchResult matchResult = continuousMatcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        updateLastTransactionPrice(security, matchResult);
        activatedOrders.addAll(tryActivateQueuedStopOrdersThenReturnTheActivated(security));

        activatedOrders.forEach(matchResult::addActivatedOrder);

        return matchResult;
    }

    private MatchResult handleUpdatedOrderByAuctionStrategy(Security security, EnterOrderRq updateOrderRq, Order order) {
        var orderBook = security.getOrderBook();
        var matcher = security.getMatcher();
        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        order.updateFromRequest(updateOrderRq);
        return matcher.executeWithoutMatching(order);
    }

}
