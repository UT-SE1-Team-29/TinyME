package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.service.matcher.AuctionMatcher;
import ir.ramtung.tinyme.domain.service.matcher.ContinuousMatcher;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.SecurityRepository;
import org.springframework.stereotype.Service;

@Service
public class SecurityConfigurationHandler {

    OrderHandler orderHandler;
    SecurityRepository securityRepository;
    EventPublisher eventPublisher;
    ContinuousMatcher continuousMatcher;
    AuctionMatcher auctionMatcher;

    public SecurityConfigurationHandler(OrderHandler orderHandler, SecurityRepository securityRepository, EventPublisher eventPublisher, ContinuousMatcher continuousMatcher, AuctionMatcher auctionMatcher) {
        this.orderHandler = orderHandler;
        this.securityRepository = securityRepository;
        this.eventPublisher = eventPublisher;
        this.continuousMatcher = continuousMatcher;
        this.auctionMatcher = auctionMatcher;
    }

    public void handleMatchingStateRq(ChangeMatchingStateRq changeMatchingStateRq) {
        var security = this.securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        var targetMatchingState = changeMatchingStateRq.getTargetState();
        eventPublisher.publish(new SecurityStateChangedEvent(
                security.getIsin(),
                targetMatchingState
        ));
        changeSecurityState(security, changeMatchingStateRq);
    }

    private void changeSecurityState(Security security, ChangeMatchingStateRq changeMatchingStateRq) {
        var targetMatchingState = changeMatchingStateRq.getTargetState();
        var prevState = security.matchingState();

        if (prevState == MatchingState.AUCTION) {
            orderHandler.handleAuctionOpening(changeMatchingStateRq); // incubating decision: even though this decision was made by the executives, but it's not the best possible way to initiate an auction opening
        }

        switch (targetMatchingState) {
            case AUCTION -> security.setMatcher(auctionMatcher);
            case CONTINUOUS -> security.setMatcher(continuousMatcher);
        }
    }
}
