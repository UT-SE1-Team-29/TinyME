package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
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
    @Builder.Default
    @Setter
    private MatchingState matchingState = MatchingState.CONTINUOUS;

    public OpeningState openingState() {
        assert matchingState == MatchingState.AUCTION;
        return orderBook.calculateOpeningState();
    }

    public void setLastTransactionPrice(int price ) {
        orderBook.setLastTransactionPrice(price);
    }
}
