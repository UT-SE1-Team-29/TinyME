package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.entity.order.Order;
import lombok.Builder;
import lombok.Singular;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

@Builder
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


}
