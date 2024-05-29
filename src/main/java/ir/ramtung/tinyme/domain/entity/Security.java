package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.service.matcher.AuctionMatcher;
import ir.ramtung.tinyme.domain.service.matcher.Matcher;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @NonNull
    @Setter
    private Matcher matcher;

    public MatchingState matchingState() {
        return matcher instanceof AuctionMatcher ? MatchingState.AUCTION
                : MatchingState.CONTINUOUS;
    }
    public OpeningState openingState() {
        assert matchingState() == MatchingState.AUCTION;
        return orderBook.calculateOpeningState();
    }

    public void setLastTransactionPrice(int price ) {
        orderBook.setLastTransactionPrice(price);
    }
}
