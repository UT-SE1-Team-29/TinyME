package ir.ramtung.tinyme.domain.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.Extensions;
import ir.ramtung.tinyme.messaging.request.MatchingState;

public interface MatchingStrategy {
    MatchResult handleNewOrder(Order order, Extensions extensions);
    MatchResult handleUpdateOrder(Order order, EnterOrderRq updateOrderRq);

    @Configuration
    class Config {
        @Bean 
        public Map<MatchingState, MatchingStrategy> matchingStrategyMap(Map<String, MatchingStrategy> matchingStrategies) {
            Map<MatchingState, MatchingStrategy> matchingStrategyMap = new HashMap<>();
            matchingStrategyMap.put(MatchingState.CONTINUOUS, matchingStrategies.get("ContinuousMatchingStrategy"));
            matchingStrategyMap.put(MatchingState.AUCTION, matchingStrategies.get("AuctionMatchingStrategy"));
            return matchingStrategyMap;
        }
    }
}
