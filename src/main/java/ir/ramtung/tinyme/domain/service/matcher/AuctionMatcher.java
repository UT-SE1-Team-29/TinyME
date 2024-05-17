package ir.ramtung.tinyme.domain.service.matcher;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.order.Order;
import org.springframework.stereotype.Service;

@Service
public class AuctionMatcher implements Matcher {
    // TODO: HUGE TODO
    @Override
    public MatchResult match(Order newOrder) {
        return null;
    }

    @Override
    public MatchResult execute(Order order) {
        return null;
    }

}
