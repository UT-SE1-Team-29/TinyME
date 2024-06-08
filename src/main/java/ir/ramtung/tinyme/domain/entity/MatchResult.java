package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.Event;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.event.OrderUpdatedEvent;
import ir.ramtung.tinyme.messaging.event.TradeEvent;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.Singular;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Builder(builderClassName = "MatchResultBuilder", toBuilder = true)
public final class MatchResult {
    private final MatchingOutcome outcome;
    private final Order remainder;
    @Singular
    private final List<Trade> trades = new LinkedList<>();
    @Singular
    private final List<Order> activatedOrders = new LinkedList<>();

    public static MatchResult auctionExecuted(List<Trade> trades) {
        return new MatchResult(null, trades);
    }

    public static MatchResult executed(Order remainder, List<Trade> trades) {
        return new MatchResult(remainder, trades);
    }

    public static MatchResult notEnoughCredit() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_CREDIT, null);
    }
    public static MatchResult notEnoughPositions() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_POSITIONS, null);
    }

    public static MatchResult minimumQuantityConditionFailed() {
        return new MatchResult(MatchingOutcome.MINIMUM_QUANTITY_CONDITION_FAILED, null);
    }

    public static MatchResult minimumQuantityConditionForAuctionMode() {
        return new MatchResult(MatchingOutcome.MINIMUM_QUANTITY_CONDITION_FOR_AUCTION_MODE, null);
    }

    private MatchResult(Order remainder, List<Trade> trades) {
        this(MatchingOutcome.EXECUTED, remainder);
        this.trades.addAll(trades);
    }

    private MatchResult(MatchingOutcome outcome, Order remainder) {
        this.outcome = outcome;
        this.remainder = remainder;
    }

    public MatchingOutcome outcome() {
        return outcome;
    }
    public Order remainder() {
        return remainder;
    }

    public LinkedList<Trade> trades() {
        return (LinkedList<Trade>) trades;
    }

    public List<Order> activatedOrders() {
        return activatedOrders;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchResult) obj;
        return Objects.equals(this.remainder, that.remainder) &&
                Objects.equals(this.trades, that.trades);
    }

    public void addActivatedOrder(Order order) {
        activatedOrders.add(order);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remainder, trades);
    }

    @Override
    public String toString() {
        return "MatchResult[" +
                "remainder=" + remainder + ", " +
                "trades=" + trades + ']';
    }

    public void publishOutcome(EventPublisher eventPublisher, EnterOrderRq enterOrderRq) {
        Event event = switch(outcome) {
            case NOT_ENOUGH_CREDIT -> new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT));
            case NOT_ENOUGH_POSITIONS -> new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS));
            case MINIMUM_QUANTITY_CONDITION_FAILED -> new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.MINIMUM_EXECUTION_QUANTITY_FAILED));
            case MINIMUM_QUANTITY_CONDITION_FOR_AUCTION_MODE -> new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.INVALID_MINIMUM_EXECUTION_QUANTITY_FOR_AUCTION_MODE));
            default -> switch(enterOrderRq.getRequestType()) {
                case NEW_ORDER -> new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId());
                case UPDATE_ORDER -> new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId());
            };
        };
        eventPublisher.publish(event);
    }

    public void publishActivatedOrderEvents(EventPublisher eventPublisher, long requestId) {
        activatedOrders.forEach(activatedOrder ->
                eventPublisher.publish(new OrderActivatedEvent(activatedOrder.getOrderId(), requestId))
        );
    }

    public void publishExecutionEventIfAny(EventPublisher eventPublisher, EnterOrderRq enterOrderRq) {
        if (!trades.isEmpty()) {
            eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        }
    }

    public void publishAuctionExecutionOutcome(EventPublisher eventPublisher) {
        if (trades.isEmpty()) {
            eventPublisher.publish(new OrderRejectedEvent());
        } else {
            eventPublisher.publish(new OrderExecutedEvent());
        }
    }

    public void publishAuctionTrades(EventPublisher eventPublisher) {
        trades.forEach(trade -> eventPublisher.publish(new TradeEvent(
                trade.security.getIsin(),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getBuy().getOrderId(),
                trade.getSell().getOrderId())));
    }

}
