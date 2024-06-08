package ir.ramtung.tinyme.domain.service;

import org.springframework.stereotype.Service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.Extensions;
import lombok.RequiredArgsConstructor;

@Service("AuctionMatchingStrategy")
@RequiredArgsConstructor
public class AuctionMatchingStrategy implements MatchingStrategy {
    final Matcher matcher;

    @Override
    public MatchResult handleNewOrder(Order order, Extensions extensions) {
        if (extensions.minimumExecutionQuantity() != 0) {
            return MatchResult.minimumQuantityConditionForAuctionMode();
        }
        return matcher.executeWithoutMatching(order);
    }

    @Override
    public MatchResult handleUpdateOrder(Order order, EnterOrderRq updateOrderRq) {
        var security = order.getSecurity();
        var orderBook = security.getOrderBook();
        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        order.updateFromRequest(updateOrderRq);
        return matcher.executeWithoutMatching(order);
    }

    
}