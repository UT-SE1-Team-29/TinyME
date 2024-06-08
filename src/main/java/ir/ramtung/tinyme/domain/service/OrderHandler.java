package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderHandler {
    final SecurityRepository securityRepository;
    final BrokerRepository brokerRepository;
    final ShareholderRepository shareholderRepository;
    final EventPublisher eventPublisher;
    final Matcher matcher;
    final Map<MatchingState, MatchingStrategy> matchingStrategies; // Node: associated bean needs further configurations whenever new matching strategies are added

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);
        } catch (InvalidRequestException ex) {
            ex.publishEvent(eventPublisher, enterOrderRq);
            return;
        }

        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
        Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

        Order order = getOrder(enterOrderRq, security, broker, shareholder);

        try {
            if (enterOrderRq.getRequestType() == OrderEntryType.UPDATE_ORDER) {
                validateUpdateOrderRequest(order, enterOrderRq.getExtensions());
            }
        } catch (InvalidRequestException ex) {
            ex.publishEvent(eventPublisher, enterOrderRq);
            return;
        }

        MatchResult matchResult = switch (enterOrderRq.getRequestType()) {
            case NEW_ORDER -> handleNewOrder(order, enterOrderRq.getExtensions());
            case UPDATE_ORDER -> handleUpdateOrder(order, enterOrderRq);
        };
        publishEnterOrderRqMessages(enterOrderRq, matchResult, security);
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            Order order = security.getOrderBook().findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
            if (order == null)
                throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
            if (order.getSide() == Side.BUY)
                order.getBroker().increaseCreditBy(order.getValue());
            security.getOrderBook().removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            ex.publishEvent(eventPublisher, deleteOrderRq);
        }
    }

    public void handleAuctionOpening(ChangeMatchingStateRq changeMatchingStateRq) {
        var security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        MatchResult matchResult = matcher.executeAuction(security);

        security.updateLastTransactionPrice(matchResult);

        var activatedOrders = security.tryActivateAll();
        activatedOrders.forEach(matchResult::addActivatedOrder);
        
        publishAuctionMessages(changeMatchingStateRq, matchResult);
    }

    protected MatchResult handleNewOrder(Order order, Extensions extensions) {
        var security = order.getSecurity();
        if (doesNotHaveEnoughPositions(order)) {
            return MatchResult.notEnoughPositions();
        }

        MatchingStrategy matchingStrategy = matchingStrategies.get(security.getMatchingState());
        return matchingStrategy.handleNewOrder(order, extensions);
    }

    protected MatchResult handleUpdateOrder(Order order, EnterOrderRq updateOrderRq) {
        if (doesNotHaveEnoughPositions(order, updateOrderRq)) {
            return MatchResult.notEnoughPositions();
        }

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }

        MatchingStrategy matchingStrategy = matchingStrategies.get(order.getSecurity().getMatchingState());
        return matchingStrategy.handleUpdateOrder(order, updateOrderRq);
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

    private Order getOrder(EnterOrderRq enterOrderRq, Security security, Broker broker, Shareholder shareholder) {
        return switch (enterOrderRq.getRequestType()) {
            case NEW_ORDER -> buildOrder(enterOrderRq, security, broker, shareholder);
            case UPDATE_ORDER ->
                    security.getOrderBook().findByOrderId(enterOrderRq.getSide(), enterOrderRq.getOrderId());
        };
    }

    private Order buildOrder(EnterOrderRq enterOrderRq, Security security, Broker broker, Shareholder shareholder) {
        var extensions = enterOrderRq.getExtensions();
        if (extensions.peakSize() > 0)
            return new IcebergOrder(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), extensions.peakSize());
        if (extensions.stopPrice() > 0) {
            return new StopOrder(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), extensions.stopPrice());
        }
        return new Order(enterOrderRq.getOrderId(), security, enterOrderRq.getSide(),
                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime());
    }

    private void publishEnterOrderRqMessages(EnterOrderRq enterOrderRq, MatchResult matchResult, Security security) {
        matchResult.publishOutcomeEvent(eventPublisher, enterOrderRq);
        security.publishOpeningPriceEvent(eventPublisher);
        matchResult.publishActivatedOrderEvents(eventPublisher, enterOrderRq.getRequestId());
        matchResult.publishExecutionEventIfAny(eventPublisher, enterOrderRq);
    }

    private void publishAuctionMessages(ChangeMatchingStateRq changeMatchingStateRq, MatchResult matchResult) {
        matchResult.publishAuctionExecutionOutcome(eventPublisher);
        matchResult.publishAuctionTrades(eventPublisher);
        matchResult.publishActivatedOrderEvents(eventPublisher, changeMatchingStateRq.getRequestId());
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

    private boolean doesNotHaveEnoughPositions(Order order, EnterOrderRq updateOrderRq) {
        var security = order.getSecurity();
        var orderBook = security.getOrderBook();
        return updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(security,
                        orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity());
    }

    private static boolean doesNotHaveEnoughPositions(Order order) {
        var security = order.getSecurity();
        var shareholder = order.getShareholder();
        var quantity = order.getQuantity();
        return order.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(security,
                        security.getOrderBook().totalSellQuantityByShareholder(shareholder) + quantity);
    }

    private void validateUpdateOrderRequest(Order order, Extensions newExtensions) throws InvalidRequestException {
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && newExtensions.peakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && newExtensions.peakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if ((order instanceof StopOrder) && order.isActive() && newExtensions.stopPrice() != 0)
            throw new InvalidRequestException(Message.INVALID_STOP_PRICE);
        if (!(order instanceof StopOrder) && newExtensions.stopPrice() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_A_NON_STOP_ORDER);
    }
}
