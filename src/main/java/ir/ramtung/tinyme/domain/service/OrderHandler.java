package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.*;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderHandler {
    final SecurityRepository securityRepository;
    final BrokerRepository brokerRepository;
    final ShareholderRepository shareholderRepository;
    public final EventPublisher eventPublisher;
    final SecurityHandler securityHandler;

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult = switch (enterOrderRq.getRequestType()) {
                case NEW_ORDER -> securityHandler.newOrder(security, enterOrderRq, broker, shareholder);
                case UPDATE_ORDER -> securityHandler.updateOrder(security, enterOrderRq);
            };
            publishEnterOrderRqMessages(enterOrderRq, matchResult, security);
        } catch (InvalidRequestException ex) {
            ex.publishEvent(eventPublisher, enterOrderRq);
        }
    }

    private void publishEnterOrderRqMessages(EnterOrderRq enterOrderRq, MatchResult matchResult, Security security) {
        matchResult.publishOutcome(eventPublisher, enterOrderRq);
        security.publishOpeningPriceEvent(eventPublisher);
        matchResult.publishActivatedOrderEvents(eventPublisher, enterOrderRq.getRequestId());
        matchResult.publishExecutionEventIfAny(eventPublisher, enterOrderRq);
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            securityHandler.deleteOrder(security, deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            ex.publishEvent(eventPublisher, deleteOrderRq);
        }
    }

    public void handleAuctionOpening(ChangeMatchingStateRq changeMatchingStateRq) {
        var security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        assert security.getMatchingState() == MatchingState.AUCTION;
        MatchResult matchResult = securityHandler.executeAuction(security);
        publishAuctionMessages(changeMatchingStateRq, matchResult, security);
    }

    private void publishAuctionMessages(ChangeMatchingStateRq changeMatchingStateRq, MatchResult matchResult, Security security) {
        matchResult.publishAuctionExecutionOutcome(eventPublisher);
        matchResult.publishAuctionTrades(eventPublisher);
        matchResult.publishActivatedOrderEvents(eventPublisher, changeMatchingStateRq.getRequestId());
    }

    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        var extensions = enterOrderRq.getExtensions();

        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (extensions.peakSize() < 0 || extensions.peakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if (extensions.minimumExecutionQuantity() < 0 || extensions.minimumExecutionQuantity() > enterOrderRq.getQuantity())
            errors.add(Message.INVALID_MINIMUM_EXECUTION_QUANTITY);
        if (extensions.stopPrice() < 0)
            errors.add(Message.INVALID_STOP_PRICE);
        if(extensions.stopPrice() > 0 && extensions.minimumExecutionQuantity() > 0)
            errors.add(Message.INVALID_MINIMUM_EXECUTION_QUANTITY_FOR_STOP_ORDERS);
        if(extensions.stopPrice() > 0 && extensions.peakSize() > 0)
            errors.add(Message.INVALID_PEAK_SIZE_FOR_STOP_ORDERS);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
}
