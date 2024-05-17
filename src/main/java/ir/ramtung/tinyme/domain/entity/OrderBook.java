package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.queues.Queue;
import ir.ramtung.tinyme.domain.entity.queues.SelectiveQueue;
import lombok.Getter;
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

    /**
     * returns null if any of the sell queue or buy queue are empty
      */
    public Integer calculateOpeningPrice() {
        if (buyQueue.isEmpty() || sellQueue.isEmpty()) {
            return null;
        }
        int minBuyPrice = buyQueue.stream().min(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int minSellPrice = sellQueue.stream().min(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int maxBuyPrice = buyQueue.stream().max(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int maxSellPrice = sellQueue.stream().max(Comparator.comparingInt(Order::getPrice)).map(Order::getPrice).get();
        int minPrice = Math.min(minBuyPrice, minSellPrice);
        int maxPrice = Math.max(maxBuyPrice, maxSellPrice);

        List<Integer> candidateList = new ArrayList<>();
        int maxTradedQuantity = 0;
        for(int price = minPrice; price <= maxPrice; price++) {
            int candidatePrice = price;
            int buyQuantity = (int) buyQueue.stream().filter(order -> order.getPrice() >= candidatePrice).count();
            int sellQuantity = (int) sellQueue.stream().filter(order -> order.getPrice() <= candidatePrice).count();
            int tradedQuantity = Math.min(buyQuantity, sellQuantity);
            if (tradedQuantity > maxTradedQuantity) {
                maxTradedQuantity = tradedQuantity;
                candidateList.clear();
            } else if (tradedQuantity == maxTradedQuantity) {
                candidateList.add(candidatePrice);
            } else {
                continue; // do nothing
            }
        }

        if (candidateList.isEmpty()) {
            return null;
        }
        if (lastTransactionPrice == null) {
            return candidateList.get(0);
        }
        return candidateList.stream()
                .min((o1, o2) -> Math.abs(o1 - lastTransactionPrice) - Math.abs(o2 - lastTransactionPrice))
                .get();
    }
}
