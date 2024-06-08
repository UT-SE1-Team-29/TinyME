package ir.ramtung.tinyme.domain.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.MatchingOutcome;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.Extensions;
import lombok.RequiredArgsConstructor;

@Service("ContinuousMatchingStrategy")
@RequiredArgsConstructor
public class ContinuousMatchingStrategy implements MatchingStrategy {
    final Matcher matcher;

    @Override
    public MatchResult handleNewOrder(Order order, Extensions extensions) {
        var security = order.getSecurity();
        List<Order> activatedOrders = new ArrayList<>();
        if (order instanceof StopOrder stopOrder && security.tryActivate(stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        var matchResult = matcher.executeWithMinimumQuantityCondition(order, extensions.minimumExecutionQuantity());

        security.updateLastTransactionPrice(matchResult);
        activatedOrders.addAll(security.tryActivateAll());
        activatedOrders.forEach(matchResult::addActivatedOrder);
        return matchResult;
    }

    @Override
    public MatchResult handleUpdateOrder(Order order, EnterOrderRq updateOrderRq) {
        var security = order.getSecurity();
        var orderBook = security.getOrderBook();

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!doesLosePriority(originalOrder, updateOrderRq)) {
            order.rollbackCreditIfBuyOrder();
            return MatchResult.executed(null, List.of());
        }

        order.markAsNew();
        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        List<Order> activatedOrders = new ArrayList<>();
        if (order instanceof StopOrder stopOrder && security.tryActivate(stopOrder)) {
            activatedOrders.add(stopOrder);
        }

        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            originalOrder.rollbackCreditIfBuyOrder();
        }
        security.updateLastTransactionPrice(matchResult);
        activatedOrders.addAll(security.tryActivateAll());
        activatedOrders.forEach(matchResult::addActivatedOrder);

        return matchResult;
    }

    private boolean doesLosePriority(Order originalOrder, EnterOrderRq updateOrderRq) {
        var updatedExtensions = updateOrderRq.getExtensions();
        return originalOrder.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != originalOrder.getPrice()
                || ((originalOrder instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updatedExtensions.peakSize()))
                || ((originalOrder instanceof StopOrder stopOrder) && (
                (stopOrder.getSide() == Side.BUY && stopOrder.getStopPrice() > updatedExtensions.stopPrice())
                        || (stopOrder.getSide() == Side.SELL && stopOrder.getStopPrice() < updatedExtensions.stopPrice())));
    }
}
