package ir.ramtung.tinyme.domain.service.matcher;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.Trade;
import ir.ramtung.tinyme.domain.entity.order.Order;

import java.util.LinkedList;
import java.util.ListIterator;

public interface Matcher {
    default MatchResult executeWithoutMatching(Order order) {
        if (order.getSide() == Side.BUY) {
            if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                return MatchResult.notEnoughCredit();
            }
            order.getBroker().decreaseCreditBy(order.getValue());
        }
        order.getSecurity().getOrderBook().enqueue(order);
        return MatchResult.executed(order, new LinkedList<>());
    }
    static void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        switch (newOrder.getSide()) {
            case BUY -> rollbackTradesForBuyOrders(newOrder, trades);
            case SELL -> rollbackTradesForSellOrders(newOrder, trades);
        }
    }

    static void rollbackRemainder(Order newOrder, Order remainder) {
        assert newOrder.getSide() == Side.BUY;
        newOrder.getBroker().increaseCreditBy((long) remainder.getQuantity() * remainder.getPrice());
    }

    private static void rollbackTradesForBuyOrders(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.BUY;
        newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreSellOrder(it.previous().getSell());
        }
    }

    private static void rollbackTradesForSellOrders(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.SELL;
        newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getBuy().getBroker().increaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreBuyOrder(it.previous().getBuy());
        }
    }
}
