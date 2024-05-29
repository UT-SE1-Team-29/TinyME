package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderHandler {
    final SecurityRepository securityRepository;
    final BrokerRepository brokerRepository;
    final ShareholderRepository shareholderRepository;
    final EventPublisher eventPublisher;
    final SecurityHandler securityHandler;

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                matchResult = securityHandler.newOrder(security, enterOrderRq, broker, shareholder);
            else
                matchResult = securityHandler.updateOrder(security, enterOrderRq);

            if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
                return;
            }
            if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
                return;
            }
            if (matchResult.outcome() == MatchingOutcome.MINIMUM_QUANTITY_CONDITION_FAILED) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.MINIMUM_EXECUTION_QUANTITY_FAILED)));
                return;
            }
            if (matchResult.outcome() == MatchingOutcome.MINIMUM_QUANTITY_CONDITION_FOR_AUCTION_MODE) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.INVALID_MINIMUM_EXECUTION_QUANTITY_FOR_AUCTION_MODE)));
                return;
            }
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            else
                eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));

            if (security.matchingState() == MatchingState.AUCTION) {
                var openingState = security.openingState();
                eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), openingState.price(), openingState.tradableQuantity()));
            }

            matchResult.activatedOrders().forEach(activatedOrder ->
                    eventPublisher.publish(new OrderActivatedEvent(activatedOrder.getOrderId(), enterOrderRq.getRequestId()))
            );

            if (!matchResult.trades().isEmpty()) {
                eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
            }
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            securityHandler.deleteOrder(security, deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleAuctionOpening(ChangeMatchingStateRq changeMatchingStateRq) {
        var security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        assert security.matchingState() == MatchingState.AUCTION;
        MatchResult matchResult = securityHandler.executeAuction(security);

        if (matchResult.trades().isEmpty()) {
            eventPublisher.publish(new OrderRejectedEvent());
        } else {
            eventPublisher.publish(new OrderExecutedEvent());
        }

        matchResult.trades().forEach(trade -> eventPublisher.publish(new TradeEvent(
                security.getIsin(),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getBuy().getOrderId(),
                trade.getSell().getOrderId())));
        matchResult.activatedOrders().forEach(activatedOrder ->
                eventPublisher.publish(new OrderActivatedEvent(activatedOrder.getOrderId(), changeMatchingStateRq.getRequestId()))
        );
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
