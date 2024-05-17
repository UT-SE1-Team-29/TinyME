package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.queues.Queue;
import ir.ramtung.tinyme.domain.entity.queues.SelectiveQueue;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

@Getter
@Setter
public class OrderBook {
    private final Queue buyQueue;
    private final Queue sellQueue;
    private Integer lastTransactionPrice = null;

    public OrderBook() {
        buyQueue = new SelectiveQueue();
        sellQueue = new SelectiveQueue();
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.queue();
        it.add(order);
    }

    private Queue getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        Queue queue = getQueue(order.getSide());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreSellOrder(Order sellOrder) {
        removeByOrderId(Side.SELL, sellOrder.getOrderId());
        putBack(sellOrder);
    }

    public void restoreBuyOrder(Order buyOrder) {
        removeByOrderId(Side.BUY, buyOrder.getOrderId());
        putBack(buyOrder);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public void removeFirst(Side side) {
        getQueue(side).removeFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    @NonNull
    public OpeningState calculateOpeningState() {
        var activatedBuyOrders = buyQueue.stream().filter(Order::isActive).toList();
        var activatedSellOrders = sellQueue.stream().filter(Order::isActive).toList();

        if (activatedBuyOrders.isEmpty() || activatedSellOrders.isEmpty()) {
            return new OpeningState(0, null);
        }

        int minBuyPrice = activatedBuyOrders.stream().min(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int minSellPrice = activatedSellOrders.stream().min(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int maxBuyPrice = activatedBuyOrders.stream().max(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int maxSellPrice = activatedSellOrders.stream().max(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int minPrice = Math.min(minBuyPrice, minSellPrice);
        int maxPrice = Math.max(maxBuyPrice, maxSellPrice);

        List<Integer> candidateList = new ArrayList<>();
        int maxTradableQuantity = 0;
        for(int price = minPrice; price <= maxPrice; price++) {
            int candidatePrice = price;
            int buyQuantity = (int) activatedBuyOrders.stream()
                    .filter(order -> order.getPrice() >= candidatePrice)
                    .mapToLong(Order::getTotalQuantity)
                    .sum();

            int sellQuantity = (int) activatedSellOrders.stream()
                    .filter(order -> order.getPrice() <= candidatePrice)
                    .mapToLong(Order::getTotalQuantity)
                    .sum();

            int tradedQuantity = Math.min(buyQuantity, sellQuantity);


            if (tradedQuantity > maxTradableQuantity) {
                maxTradableQuantity = tradedQuantity;
                candidateList.clear();
                candidateList.add(candidatePrice);
            } else if (tradedQuantity == maxTradableQuantity) {
                candidateList.add(candidatePrice);
            } else {
                continue; // do nothing
            }
        }

        if (candidateList.isEmpty()) {
            return new OpeningState(0, null);
        }
        if (lastTransactionPrice == null) {
            return new OpeningState(maxTradableQuantity, candidateList.get(0));
        }
        var bestPrice = candidateList.stream()
                .min((o1, o2) -> Math.abs(o1 - lastTransactionPrice) - Math.abs(o2 - lastTransactionPrice))
                .get();
        return new OpeningState(maxTradableQuantity, bestPrice);
    }
}
