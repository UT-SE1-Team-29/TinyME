package ir.ramtung.tinyme.domain.service.matcher;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class ContinuousMatcher implements Matcher {
    @Override
    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueue(icebergOrder);
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }
        return MatchResult.executed(newOrder, trades);
    }

    @Override
    public MatchResult execute(Order order) {
        if (!order.isActive()) { // inactive orders
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueue(order);
            return MatchResult.executed(order, new LinkedList<>());
        }

        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT)
            return result;

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
        return result;
    }

    @Override
    public MatchResult executeWithMinimumQuantityCondition(Order order, int minimumExecutionQuantity) {
        int originalQuantity = order.getTotalQuantity();
        MatchResult result = execute(order);
        if (order instanceof StopOrder) return result;
        if (result.remainder() == null) return result;

        var tradedQuantity = originalQuantity - result.remainder().getTotalQuantity();
        if (tradedQuantity < minimumExecutionQuantity) {
            rollbackTrades(order, result.trades());
            if (order.getSide() == Side.BUY) {
                rollbackRemainder(order, result.remainder());
            }
            return MatchResult.minimumQuantityConditionFailed();
        }
        return result;
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        switch (newOrder.getSide()) {
            case BUY -> rollbackTradesForBuyOrders(newOrder, trades);
            case SELL -> rollbackTradesForSellOrders(newOrder, trades);
        }
    }

    private void rollbackTradesForBuyOrders(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.BUY;
        newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreSellOrder(it.previous().getSell());
        }
    }

    private void rollbackTradesForSellOrders(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.SELL;
        newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getBuy().getBroker().increaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreBuyOrder(it.previous().getBuy());
        }
    }

    private void rollbackRemainder(Order newOrder, Order remainder) {
        assert newOrder.getSide() == Side.BUY;
        newOrder.getBroker().increaseCreditBy((long) remainder.getQuantity() * remainder.getPrice());
    }

}
