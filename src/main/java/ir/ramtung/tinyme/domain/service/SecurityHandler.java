package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.Extensions;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SecurityHandler {
    final Matcher matcher;

    public MatchResult newOrder(Security security, EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) {
        var orderBook = security.getOrderBook();
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(security,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity())) {
            return MatchResult.notEnoughPositions();
        }
        Order order = getOrder(enterOrderRq, security, broker, shareholder);
        var extensions = enterOrderRq.getExtensions();

        return switch (security.getMatchingState()) {
            case CONTINUOUS -> handleNewOrderByContinuousStrategy(order, extensions);
            case AUCTION -> handleNewOrderByAuctionStrategy(order, extensions);
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

        validateUpdateOrderRequest(updateOrderRq.getExtensions(), order);
        if (doesNotHaveEnoughPositions(security, updateOrderRq, order)) {
            return MatchResult.notEnoughPositions();
        }

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }

        return switch (security.getMatchingState()) {
            case CONTINUOUS -> handleUpdatedOrderByContinuousStrategy(security, updateOrderRq, order);
            case AUCTION -> handleUpdatedOrderByAuctionStrategy(security, updateOrderRq, order);
        };
    }

    public MatchResult executeAuction(Security security) {
        assert security.getMatchingState() == MatchingState.AUCTION;
        var matchResult = matcher.executeAuction(security);

        updateLastTransactionPrice(security, matchResult);

        var activatedOrders = security.tryActivateAll();
        activatedOrders.forEach(matchResult::addActivatedOrder);
        return matchResult;
    }

    private boolean doesLosePriority(EnterOrderRq updateOrderRq, Order originalOrder) {
        var updatedExtensions = updateOrderRq.getExtensions();
        return originalOrder.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != originalOrder.getPrice()
                || ((originalOrder instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updatedExtensions.peakSize()))
                || ((originalOrder instanceof StopOrder stopOrder) && (
                (stopOrder.getSide() == Side.BUY && stopOrder.getStopPrice() > updatedExtensions.stopPrice())
                        || (stopOrder.getSide() == Side.SELL && stopOrder.getStopPrice() < updatedExtensions.stopPrice())));
    }

    private boolean doesNotHaveEnoughPositions(Security security, EnterOrderRq updateOrderRq, Order order) {
        var orderBook = security.getOrderBook();
        return updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(security,
                        orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity());
    }

    private void validateUpdateOrderRequest(Extensions extensions, Order order) throws InvalidRequestException {
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && extensions.peakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && extensions.peakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if ((order instanceof StopOrder) && order.isActive() && extensions.stopPrice() != 0)
            throw new InvalidRequestException(Message.INVALID_STOP_PRICE);
        if (!(order instanceof StopOrder) && extensions.stopPrice() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_A_NON_STOP_ORDER);
    }

    private Order getOrder(EnterOrderRq enterOrderRq, Security security, Broker broker, Shareholder shareholder) {
        var extensions = enterOrderRq.getExtensions();
        if (extensions.peakSize() > 0)
            return new IcebergOrder(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), extensions.peakSize());
        if (extensions.stopPrice() > 0) {
            return new StopOrder(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), extensions.stopPrice());
        }
        return new Order(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime());
    }

    private void updateLastTransactionPrice(Security security, MatchResult result) {
        assert result != null;
        if (!result.trades().isEmpty()) {
            security.setLastTransactionPrice(result.trades().getLast().getPrice());
        }
    }

    private MatchResult handleNewOrderByAuctionStrategy(Order order, Extensions extensions) {
        if (extensions.minimumExecutionQuantity() != 0) {
            return MatchResult.minimumQuantityConditionForAuctionMode();
        }
        return matcher.executeWithoutMatching(order);
    }

    private MatchResult handleNewOrderByContinuousStrategy(Order order, Extensions extensions) {
        var security = order.getSecurity();
        List<Order> activatedOrders = new ArrayList<>();
        if (order instanceof StopOrder stopOrder && security.tryActivate(stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        var matchResult = matcher.executeWithMinimumQuantityCondition(order, extensions.minimumExecutionQuantity());

        updateLastTransactionPrice(security, matchResult);

        activatedOrders.addAll(security.tryActivateAll());
        activatedOrders.forEach(matchResult::addActivatedOrder);
        return matchResult;
    }

    private MatchResult handleUpdatedOrderByContinuousStrategy(Security security, EnterOrderRq updateOrderRq, Order order) {
        var orderBook = security.getOrderBook();

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
        if (order instanceof StopOrder stopOrder && security.tryActivate(stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        updateLastTransactionPrice(security, matchResult);
        activatedOrders.addAll(security.tryActivateAll());

        activatedOrders.forEach(matchResult::addActivatedOrder);

        return matchResult;
    }

    private MatchResult handleUpdatedOrderByAuctionStrategy(Security security, EnterOrderRq updateOrderRq, Order order) {
        var orderBook = security.getOrderBook();
        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        order.updateFromRequest(updateOrderRq);
        return matcher.executeWithoutMatching(order);
    }

}
