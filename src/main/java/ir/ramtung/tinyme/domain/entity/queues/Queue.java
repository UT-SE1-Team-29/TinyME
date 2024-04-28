package ir.ramtung.tinyme.domain.entity.queues;

import ir.ramtung.tinyme.domain.entity.order.Order;

import java.util.List;

public interface Queue extends List<Order> {
    Order getFirst();

    Order removeFirst();

    void addFirst(Order order);
}
