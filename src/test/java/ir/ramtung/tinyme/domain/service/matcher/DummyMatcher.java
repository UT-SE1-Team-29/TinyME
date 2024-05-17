package ir.ramtung.tinyme.domain.service.matcher;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.order.Order;

public class DummyMatcher implements Matcher {
    @Override
    public MatchResult match(Order newOrder) {
        return null;
    }

    @Override
    public MatchResult execute(Order order) {
        return null;
    }
}
