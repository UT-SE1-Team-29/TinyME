package ir.ramtung.tinyme.domain.entity.queues;

import ir.ramtung.tinyme.domain.entity.order.Order;

import java.util.LinkedList;
import java.util.NoSuchElementException;

public class SelectiveQueue extends LinkedList<Order> implements Queue {
    @Override
    public Order getFirst() {
        for (Order order : this) {
            if (order.isActive()) {
                return order;
            }
        }
        throw new NoSuchElementException();
    }

    @Override
    public Order removeFirst() {
        for (Order order : this) {
            if (order.isActive()) {
                removeFirstOccurrence(order);
                return order;
            }
        }
        throw new NoSuchElementException();
    }
}

