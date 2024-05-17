package ir.ramtung.tinyme.domain.service.matcher;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.order.Order;

public interface Matcher {
    MatchResult match(Order newOrder);
    MatchResult execute(Order order);
    MatchResult executeWithMinimumQuantityCondition(Order order, int minimumExecutionQuantity);
}
