package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import lombok.Builder;
import lombok.Getter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    private Integer lastTransactionPrice = null;

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        Order order = getOrder(enterOrderRq, broker, shareholder);

        boolean hasActivated;
        if (order instanceof StopOrder stopOrder) {
            hasActivated = activateIfPossible(stopOrder);
        } else {
            hasActivated = false;
        }

        MatchResult matchResult = matcher.executeWithMinimumQuantityCondition(order, enterOrderRq.getMinimumExecutionQuantity());
        updateLastTransactionPrice(matchResult);

        List<Order> activatedOrders = activateQueuedOrdersIfPossibleThenGetThem();

        if (hasActivated) matchResult.addActivatedOrder(order);
        activatedOrders.forEach(matchResult::addActivatedOrder);

        return matchResult;
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        }
        else
            order.markAsNew();

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        updateLastTransactionPrice(matchResult);
        List<Order> activatedOrders = activateQueuedOrdersIfPossibleThenGetThem();
        activatedOrders.forEach(matchResult::addActivatedOrder);
        return matchResult;
    }

    private Order getOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) {
        if (enterOrderRq.getPeakSize() > 0)
            return new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize());
        if (enterOrderRq.getStopPrice() > 0) {
            return new StopOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), enterOrderRq.getStopPrice());
        }
        return new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime());
    }

    private void updateLastTransactionPrice(MatchResult result) {
        if (!result.trades().isEmpty())
            lastTransactionPrice = result.trades().getLast().getPrice();
    }

    private boolean activateIfPossible(Order order) {
        if (! (order instanceof StopOrder stopOrder)) return false;
        if (lastTransactionPrice == null) return false;
        if (stopOrder.isActive()) return false;

        var condition = (stopOrder.getSide() == Side.BUY && stopOrder.getStopPrice() <= lastTransactionPrice)
                || (stopOrder.getSide() == Side.SELL && stopOrder.getStopPrice() >= lastTransactionPrice);
        if (condition) {
            stopOrder.activate();
            return true;
        }
        return false;
    }

    private List<Order> activateQueuedOrdersIfPossibleThenGetThem() {
        List<Order> activatedOrders = new LinkedList<>();
        for (Order order : this.orderBook.getBuyQueue()) {
            boolean hasActivated = activateIfPossible(order);
            if (hasActivated) activatedOrders.add(order);
        }
        return activatedOrders;
    }
}
