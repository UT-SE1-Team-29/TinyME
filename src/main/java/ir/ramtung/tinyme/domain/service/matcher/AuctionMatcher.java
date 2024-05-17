package ir.ramtung.tinyme.domain.service.matcher;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Trade;
import ir.ramtung.tinyme.domain.entity.order.Order;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.NoSuchElementException;

@Service
public class AuctionMatcher implements Matcher {
    // TODO: HUGE TODO
    public MatchResult execute(Security security) {
        var buyQueue = security.getOrderBook().getBuyQueue();
        var sellQueue = security.getOrderBook().getSellQueue();
        var openingPrice = security.openingState().price();
        var trades = new LinkedList<Trade>();

        Order buyIt = null;
        Order sellIt = null;

        try {
            buyIt = buyQueue.removeFirst();
            sellIt = sellQueue.removeFirst();
            buyIt.getBroker().increaseCreditBy(buyIt.getValue());
            while (buyIt.getPrice() >= openingPrice && sellIt.getPrice() <= openingPrice) {
                var quantity = Math.min(buyIt.getTotalQuantity(), sellIt.getTotalQuantity());
                trades.add(new Trade(security, openingPrice, quantity, buyIt, sellIt));
                buyIt.getBroker().decreaseCreditBy((long) quantity*openingPrice);
                sellIt.getBroker().increaseCreditBy((long) quantity*openingPrice);
                buyIt.decreaseTotalQuantity(quantity);
                sellIt.decreaseTotalQuantity(quantity);

                if (sellIt.getTotalQuantity() == 0) {
                    sellIt = sellQueue.removeFirst();
                }
                if (buyIt.getTotalQuantity() == 0) {
                    buyIt = buyQueue.removeFirst();
                }
            }
        } catch (NoSuchElementException ignored) {
        }

        if (buyIt != null && buyIt.getTotalQuantity() != 0) {
            buyIt.getBroker().decreaseCreditBy(buyIt.getValue());
            security.getOrderBook().enqueue(buyIt);
        }
        if (sellIt != null && sellIt.getTotalQuantity() != 0) {
            security.getOrderBook().enqueue(sellIt);
        }

        return MatchResult.auctionExecuted(trades);
    }
}
