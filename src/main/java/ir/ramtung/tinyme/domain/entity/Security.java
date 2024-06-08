package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

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
    @Setter
    private MatchingState matchingState = MatchingState.CONTINUOUS;

    public OpeningState openingState() {
        assert matchingState == MatchingState.AUCTION;
        return orderBook.calculateOpeningState();
    }

    public void setLastTransactionPrice(int price ) {
        orderBook.setLastTransactionPrice(price);
    }

    public List<Order> tryActivateAll() {
        var orderBook = getOrderBook();
        List<Order> activatedOrders = new LinkedList<>();
        for (Order order : orderBook.getBuyQueue()) {
            tryActivate(order, activatedOrders);
        }
        return activatedOrders;
    }

    private void tryActivate(Order order, List<Order> accumulator) {
        if (tryActivate(order)) {
            accumulator.add(order);
        }
    }

    /**
     * @return true if activation happens and false otherwise
     */
    public boolean tryActivate(Order order) {
        if (order.isActive()) return false;
        if (order instanceof StopOrder stopOrder) {
            return tryActivate(stopOrder);
        }
        return false;
    }

    private boolean tryActivate(StopOrder stopOrder) {
        var orderBook = getOrderBook();
        var lastTransactionPrice = orderBook.getLastTransactionPrice();

        if (lastTransactionPrice == null) return false;

        var condition = (stopOrder.getSide() == Side.BUY && stopOrder.getStopPrice() <= lastTransactionPrice)
                || (stopOrder.getSide() == Side.SELL && stopOrder.getStopPrice() >= lastTransactionPrice);
        if (condition) {
            stopOrder.activate();
            return true;
        }
        return false;
    }
}
