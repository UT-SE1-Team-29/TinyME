package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.entity.order.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.MatchingStrategy;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.Extensions;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
class SecurityTest extends OrderHandler{
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private List<Order> orders;

    @Autowired
    public SecurityTest(
            SecurityRepository securityRepository, 
            BrokerRepository brokerRepository, 
            ShareholderRepository shareholderRepository, 
            EventPublisher eventPublisher, 
            Matcher matcher, 
            Map<MatchingState, MatchingStrategy> matchingStrategies) {
        super(securityRepository, brokerRepository, shareholderRepository, eventPublisher, matcher, matchingStrategies);
    }

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker = Broker.builder().brokerId(0).credit(1_000_000L).build();
        shareholder = Shareholder.builder().shareholderId(0).build();
        shareholder.incPosition(security, 100_000);
        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, broker, shareholder),
                new Order(2, security, BUY, 43, 15500, broker, shareholder),
                new Order(3, security, BUY, 445, 15450, broker, shareholder),
                new Order(4, security, BUY, 526, 15450, broker, shareholder),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder),
                new Order(6, security, Side.SELL, 350, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
    }

    @Test
    void reducing_quantity_does_not_change_priority() {
        var side = BUY;
        var orderId = 3;
        EnterOrderRq updateOrderRq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(orderId)
                .side(side)
                .quantity(440)
                .price(15450)
                .build();
        var order = security.getOrderBook().findByOrderId(side, orderId);
        assertThatNoException().isThrownBy(() -> processUpdateOrder(order, updateOrderRq));
        assertThat(security.getOrderBook().getBuyQueue().get(2).getQuantity()).isEqualTo(440);
        assertThat(security.getOrderBook().getBuyQueue().get(2).getOrderId()).isEqualTo(3);
    }

    @Test
    void increasing_quantity_changes_priority() {
        var side = BUY;
        var orderId = 3;
        EnterOrderRq updateOrderRq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(orderId)
                .side(side)
                .quantity(450)
                .price(15450)
                .build();
        var order = security.getOrderBook().findByOrderId(side, orderId);
        assertThatNoException().isThrownBy(() -> processUpdateOrder(order, updateOrderRq));
        assertThat(security.getOrderBook().getBuyQueue().get(3).getQuantity()).isEqualTo(450);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getOrderId()).isEqualTo(3);
    }

    @Test
    void changing_price_changes_priority() {
        var side = BUY;
        var orderId = 1;
        EnterOrderRq updateOrderRq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(orderId)
                .side(side)
                .quantity(300)
                .price(15450)
                .build();
        var order = security.getOrderBook().findByOrderId(side, orderId);
        assertThatNoException().isThrownBy(() -> processUpdateOrder(order, updateOrderRq));
        assertThat(security.getOrderBook().getBuyQueue().get(3).getQuantity()).isEqualTo(300);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getPrice()).isEqualTo(15450);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getOrderId()).isEqualTo(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getOrderId()).isEqualTo(2);
    }

    @Test
    void changing_price_causes_trades_to_happen() {
        var side = SELL;
        var orderId = 6;
        EnterOrderRq updateOrderRq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(orderId)
                .side(side)
                .quantity(350)
                .price(15700)
                .build();
        var order = security.getOrderBook().findByOrderId(side, orderId);
        assertThatNoException().isThrownBy(() ->
                assertThat(processUpdateOrder(order, updateOrderRq).trades()).isNotEmpty()
        );
    }

    @Test
    void delete_order_works() {
        DeleteOrderRq deleteOrderRq = DeleteOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .side(SELL)
                .orderId(6)
                .build();
        assertThatNoException().isThrownBy(() -> processDeleteOrder(security, deleteOrderRq));
        assertThat(security.getOrderBook().getBuyQueue()).isEqualTo(orders.subList(0, 5));
        assertThat(security.getOrderBook().getSellQueue()).isEqualTo(orders.subList(6, 10));
    }

    @Test
    void deleting_non_existing_order_fails() {
        DeleteOrderRq deleteOrderRq = DeleteOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .side(SELL)
                .orderId(1)
                .build();
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> processDeleteOrder(security, deleteOrderRq));
    }

    @Test
    void increasing_iceberg_peak_size_changes_priority() {
        security = Security.builder().build();
        broker = Broker.builder().credit(1_000_000L).build();
        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, broker, shareholder),
                new Order(2, security, BUY, 43, 15500, broker, shareholder),
                new IcebergOrder(3, security, BUY, 445, 15450, broker, shareholder, 100),
                new Order(4, security, BUY, 526, 15450, broker, shareholder),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        EnterOrderRq updateOrderRq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(3)
                .side(BUY)
                .quantity(445)
                .price(15450)
                .extensions(new Extensions(150, 0, 0))
                .build();
        assertThatNoException().isThrownBy(() -> processUpdateOrder(orders.get(2), updateOrderRq));
        assertThat(security.getOrderBook().getBuyQueue().get(3).getQuantity()).isEqualTo(150);
        assertThat(security.getOrderBook().getBuyQueue().get(3).getOrderId()).isEqualTo(3);
    }

    @Test
    void decreasing_iceberg_quantity_to_amount_larger_than_peak_size_does_not_changes_priority() {
        security = Security.builder().build();
        broker = Broker.builder().build();
        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, broker, shareholder),
                new Order(2, security, BUY, 43, 15500, broker, shareholder),
                new IcebergOrder(3, security, BUY, 445, 15450, broker, shareholder, 100),
                new Order(4, security, BUY, 526, 15450, broker, shareholder),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        EnterOrderRq updateOrderRq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(3)
                .side(BUY)
                .quantity(300)
                .price(15450)
                .extensions(new Extensions(100, 0, 0))
                .build();
        assertThatNoException().isThrownBy(() -> processUpdateOrder(orders.get(2), updateOrderRq));
        assertThat(security.getOrderBook().getBuyQueue().get(2).getOrderId()).isEqualTo(3);
    }

    @Test
    void update_iceberg_that_loses_priority_with_no_trade_works() {
        security = Security.builder().isin("TEST").build();
        broker = Broker.builder().brokerId(1).credit(100).build();

        security.getOrderBook().enqueue(
                new IcebergOrder(1, security, BUY, 100, 9, broker, shareholder, 10)
        );

        EnterOrderRq updateReq = EnterOrderRq.builder()
                .requestId(2)
                .securityIsin(security.getIsin())
                .orderId(1)
                .side(BUY)
                .quantity(100)
                .price(10)
                .extensions(new Extensions(10, 0, 0))
                .build();
        var order = security.getOrderBook().findByOrderId(BUY, 1);
        assertThatNoException().isThrownBy(() -> processUpdateOrder(order, updateReq));

        assertThat(broker.getCredit()).isEqualTo(0);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getOrderId()).isEqualTo(1);
    }

    @Test
    void update_iceberg_order_decrease_peak_size() {
        security = Security.builder().isin("TEST").build();
        security.getOrderBook().enqueue(
                new IcebergOrder(1, security, BUY, 20, 10, broker, shareholder, 10)
        );

        EnterOrderRq updateReq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(1)
                .side(BUY)
                .quantity(20)
                .price(10)
                .extensions(new Extensions(5, 0, 0))
                .build();
        var order = security.getOrderBook().findByOrderId(BUY, 1);
        assertThatNoException().isThrownBy(() -> processUpdateOrder(order, updateReq));

        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void update_iceberg_order_price_leads_to_match_as_new_order() {
        security = Security.builder().isin("TEST").build();
        shareholder.incPosition(security, 1_000);
        orders = List.of(
                new Order(1, security, BUY, 15, 10, broker, shareholder),
                new Order(2, security, BUY, 20, 10, broker, shareholder),
                new Order(3, security, BUY, 40, 10, broker, shareholder),
                new IcebergOrder(4, security, SELL, 30, 12, broker, shareholder, 10)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        EnterOrderRq updateReq = EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(4)
                .side(SELL)
                .quantity(30)
                .price(10)
                .extensions(new Extensions(10, 0, 0))
                .build();
        MatchResult result = processUpdateOrder(orders.get(3), updateReq);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(result.trades()).hasSize(2);
        assertThat(result.remainder().getQuantity()).isZero();
    }


}