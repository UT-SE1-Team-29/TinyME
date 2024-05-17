package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import ir.ramtung.tinyme.domain.service.matcher.ContinuousMatcher;
import ir.ramtung.tinyme.domain.service.matcher.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.ArrayList;
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
    @NonNull
    @Setter
    private Matcher matcher;

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity())) {
            return MatchResult.notEnoughPositions();
        }
        Order order = getOrder(enterOrderRq, broker, shareholder);

        List<Order> activatedOrders = new ArrayList<>();
        if (order instanceof StopOrder stopOrder && tryActivateStopOrder(stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        var matchResult = (matcher instanceof ContinuousMatcher continuousMatcher) ?
                continuousMatcher.executeWithMinimumQuantityCondition(order, enterOrderRq.getMinimumExecutionQuantity()) :
                null; // todo

        updateLastTransactionPrice(matchResult);

        activatedOrders.addAll(tryActivateQueuedStopOrdersThenReturnTheActivated());
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

    public MatchResult updateOrder(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        validateUpdateOrderRequest(updateOrderRq, order);
        if (doesNotHaveEnoughPositions(updateOrderRq, order)) {
            return MatchResult.notEnoughPositions();
        }

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
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
        if (order instanceof StopOrder stopOrder && tryActivateStopOrder(stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        updateLastTransactionPrice(matchResult);
        activatedOrders.addAll(tryActivateQueuedStopOrdersThenReturnTheActivated());

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

    private boolean doesNotHaveEnoughPositions(EnterOrderRq updateOrderRq, Order order) {
        return updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
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
        assert result != null;
        if (!result.trades().isEmpty())
            orderBook.setLastTransactionPrice(result.trades().getLast().getPrice());
    }

    /**
     * @return true if activation happens and false otherwise
     */
    private boolean tryActivateStopOrder(StopOrder order) {
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

    private List<Order> tryActivateQueuedStopOrdersThenReturnTheActivated() {
        List<Order> activatedOrders = new LinkedList<>();
        for (Order order : this.orderBook.getBuyQueue()) {
            if (order instanceof StopOrder stopOrder
                    && tryActivateStopOrder(stopOrder)) {
                activatedOrders.add(stopOrder);
            }
        }
        return activatedOrders;
    }
}
