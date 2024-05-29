package ir.ramtung.tinyme.domain.entity.order;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopOrder extends Order {
    int stopPrice;
    boolean active;
    public StopOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice, OrderStatus status) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status);
        this.stopPrice = stopPrice;
        this.active = false;
    }

    public StopOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, stopPrice, OrderStatus.NEW);
    }

    @Override
    public Order snapshot() {
        return new StopOrder(orderId, security, side, quantity, price, broker, shareholder, entryTime, stopPrice, OrderStatus.SNAPSHOT);
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return new StopOrder(orderId, security, side, newQuantity, price, broker, shareholder, entryTime, stopPrice, OrderStatus.SNAPSHOT);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public void activate() {
        assert !active;

        active = true;
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        var stopPrice = updateOrderRq.getExtensions().stopPrice();
        if (!this.isActive() && stopPrice != 0) {
            this.stopPrice = stopPrice;
        }
    }
}
