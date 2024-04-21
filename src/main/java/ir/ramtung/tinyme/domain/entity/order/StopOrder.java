package ir.ramtung.tinyme.domain.entity.order;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopOrder extends Order {
    int stopPrice;
    boolean hasActivated;
    public StopOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice, OrderStatus status) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status);
        this.stopPrice = stopPrice;
        this.hasActivated = false;
    }

    public StopOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, stopPrice, OrderStatus.NEW);
    }

    public StopOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int stopPrice) {
        this(orderId, security, side, quantity, price, broker, shareholder, LocalDateTime.now(), stopPrice, OrderStatus.NEW);
    }

    @Override
    public boolean isActive() {
        return hasActivated;
    }

    public void activate() {
        hasActivated = true;
    }

}
