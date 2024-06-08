package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;
import java.util.NoSuchElementException;

@Service
public class Matcher {

    public static void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        switch (newOrder.getSide()) {
            case BUY -> rollbackTradesForBuyOrders(newOrder, trades);
            case SELL -> rollbackTradesForSellOrders(newOrder, trades);
        }
    }

    public static void rollbackRemainder(Order newOrder, Order remainder) {
        assert newOrder.getSide() == Side.BUY;
        newOrder.getBroker().increaseCreditBy((long) remainder.getQuantity() * remainder.getPrice());
    }

    private static void rollbackTradesForBuyOrders(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.BUY;
        newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getSell());
        }
    }

    private static void rollbackTradesForSellOrders(Order newOrder, LinkedList<Trade> trades) {
        assert newOrder.getSide() == Side.SELL;
        newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getBuy().getBroker().increaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getBuy());
        }
    }

    public MatchResult executeWithoutMatching(Order order) {
        if (order.getSide() == Side.BUY) {
            if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                return MatchResult.notEnoughCredit();
            }
            order.getBroker().decreaseCreditBy(order.getValue());
        }
        order.getSecurity().getOrderBook().enqueue(order);
        return MatchResult.executed(order, new LinkedList<>());
    }

    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            var trade = makeTrade(newOrder, matchingOrder);
            if (trade != null) {
                trades.add(trade);
            } else {
                rollbackTrades(newOrder, trades);
                return MatchResult.notEnoughCredit();
            }
            updateQuantities(newOrder, matchingOrder);
        }
        return MatchResult.executed(newOrder, trades);
    }

    /**
     * @return null if no trade was made
     */
    private Trade makeTrade(Order newOrder, Order matchingOrder) {
        Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
        if (newOrder.getSide() == Side.BUY) {
            if (trade.buyerHasEnoughCredit())
                trade.decreaseBuyersCredit();
            else {
                return null;
            }
        }
        trade.increaseSellersCredit();
        return trade;
    }

    private void updateQuantities(Order newOrder, Order matchingOrder) {
        var orderBook = newOrder.getSecurity().getOrderBook();
        if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
            newOrder.decreaseQuantity(matchingOrder.getQuantity());
            orderBook.removeFirst(matchingOrder.getSide());
            if (matchingOrder instanceof IcebergOrder icebergOrder) {
                icebergOrder.handleQuantityDecrease(matchingOrder.getQuantity());
            }
        } else {
            matchingOrder.decreaseQuantity(newOrder.getQuantity());
            newOrder.makeQuantityZero();
        }
    }

    public MatchResult execute(Order order) {
        if (!order.isActive()) { // inactive orders
            return executeWithoutMatching(order);
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
        updatePositions(result);
        return result;
    }

    private void updatePositions(MatchResult result) {
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
    }

    public MatchResult executeWithMinimumQuantityCondition(Order order, int minimumExecutionQuantity) {
        int originalQuantity = order.getTotalQuantity();
        MatchResult result = execute(order);
        if (order instanceof StopOrder) return result;
        if (result.remainder() == null) return result;

        var tradedQuantity = originalQuantity - result.remainder().getTotalQuantity();
        if (tradedQuantity < minimumExecutionQuantity) {
            Matcher.rollbackTrades(order, result.trades());
            if (order.getSide() == Side.BUY) {
                rollbackRemainder(order, result.remainder());
            }
            return MatchResult.minimumQuantityConditionFailed();
        }
        return result;
    }

    public MatchResult executeAuction(Security security) {
        var buyQueue = security.getOrderBook().getBuyQueue();
        var sellQueue = security.getOrderBook().getSellQueue();
        var openingPrice = security.openingState().price();
        var trades = new LinkedList<Trade>();

        Order buyIt = null;
        Order sellIt = null;

        try {
            buyIt = buyQueue.removeFirst();
            sellIt = sellQueue.removeFirst();
            buyIt.getBroker().increaseCreditBy(buyIt.getValue());
            while (buyIt.getPrice() >= openingPrice && sellIt.getPrice() <= openingPrice) {
                var quantity = Math.min(buyIt.getTotalQuantity(), sellIt.getTotalQuantity());
                trades.add(new Trade(security, openingPrice, quantity, buyIt, sellIt));
                buyIt.getBroker().decreaseCreditBy((long) quantity*openingPrice);
                sellIt.getBroker().increaseCreditBy((long) quantity*openingPrice);
                buyIt.decreaseTotalQuantity(quantity);
                sellIt.decreaseTotalQuantity(quantity);

                if (sellIt.getTotalQuantity() == 0) {
                    sellIt = sellQueue.removeFirst();
                }
                if (buyIt.getTotalQuantity() == 0) {
                    buyIt = buyQueue.removeFirst();
                }
            }
        } catch (NoSuchElementException ignored) {
        }

        if (buyIt != null && buyIt.getTotalQuantity() != 0) {
            buyIt.getBroker().decreaseCreditBy(buyIt.getValue());
            security.getOrderBook().enqueue(buyIt);
        }
        if (sellIt != null && sellIt.getTotalQuantity() != 0) {
            security.getOrderBook().enqueue(sellIt);
        }

        return MatchResult.auctionExecuted(trades);
    }
}
