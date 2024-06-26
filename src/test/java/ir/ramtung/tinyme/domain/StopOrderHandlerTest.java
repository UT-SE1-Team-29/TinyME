package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.Broker;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Shareholder;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.Extensions;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class StopOrderHandlerTest {
    private Security security;
    private Broker broker1;
    private Broker broker2;
    private Broker broker3;
    private Shareholder shareholder;
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;

    @BeforeEach
    void setup() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("TEST").build();
        securityRepository.addSecurity(security);

        broker1 = Broker.builder().brokerId(1).credit(100_000_000L).build();
        brokerRepository.addBroker(broker1);

        broker2 = Broker.builder().brokerId(2).credit(100_000_000L).build();
        brokerRepository.addBroker(broker2);

        broker3 = Broker.builder().brokerId(3).credit(100_000_000L).build();
        brokerRepository.addBroker(broker3);

        shareholder = Shareholder.builder().shareholderId(1).build();
        shareholder.incPosition(security, 1_000);
        shareholderRepository.addShareholder(shareholder);
    }

    @Test
    void deleting_an_inactive_stop_order() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                10
        ));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(2, security.getIsin(), BUY, 2));

        verify(eventPublisher, times(1)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderDeletedEvent.class));
    }

    @Test
    void deleting_an_active_stop_order() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                10,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0
        ));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                2,
                security.getIsin(),
                3,
                LocalDateTime.now(),
                BUY,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                10
        ));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), BUY, 3));

        verify(eventPublisher, times(2)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderDeletedEvent.class));
    }

    @Test
    void invalid_insertion_of_a_stop_order_due_to_negative_stop_price() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.builder()
                .requestId(1)
                .securityIsin(security.getIsin())
                .orderId(2)
                .side(BUY)
                .quantity(120)
                .price(10)
                .brokerId(broker2.getBrokerId())
                .shareholderId(shareholder.getShareholderId())
                .extensions(new Extensions(0, 0, -1))
                .build()
        );

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.INVALID_STOP_PRICE
        );

        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(0);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L);

    }

    @Test
    void invalid_insertion_of_a_stop_order_due_to_minimum_execution_quantity() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                10,
                10
        ));

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.INVALID_MINIMUM_EXECUTION_QUANTITY_FOR_STOP_ORDERS
        );

        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(0);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L);

    }

    @Test
    void invalid_insertion_of_a_stop_order_due_to_peak_size_and_minimum_execution_quantity() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                54,
                4,
                10
        ));

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.INVALID_PEAK_SIZE_FOR_STOP_ORDERS,
                Message.INVALID_MINIMUM_EXECUTION_QUANTITY_FOR_STOP_ORDERS
        );

        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(0);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L);

    }

    @Test
    void insert_buy_stop_order_but_does_not_match_due_to_deactivation() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                10
        ));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(120);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L - 120 * 10L);

    }

    @Test
    void insert_sell_stop_order_but_does_not_match_due_to_deactivation() {
        security.getOrderBook().enqueue(
                new Order(1, security, BUY, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                Side.SELL,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                10
        ));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getSellQueue().get(0).getQuantity()).isEqualTo(120);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L);

    }

    @Test
    void insert_buy_stop_order_and_match() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                10,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0
        ));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                120,
                15,
                broker3.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                10
        ));

        verify(eventPublisher, times(2)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderActivatedEvent.class));
        verify(eventPublisher, times(2)).publish(any(OrderExecutedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(0);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(30);
        assertThat(broker3.getCredit()).isEqualTo(100_000_000L - 90 * 10L - 30 * 15L);
    }

    @Test
    void insert_sell_stop_order_and_match() {
        security.getOrderBook().enqueue(
                new Order(1, security, BUY, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                Side.SELL,
                10,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0
        ));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                Side.SELL,
                120,
                8,
                broker3.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                10
        ));

        verify(eventPublisher, times(2)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderActivatedEvent.class));
        verify(eventPublisher, times(2)).publish(any(OrderExecutedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(0);
        assertThat(security.getOrderBook().getSellQueue().get(0).getQuantity()).isEqualTo(30);
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L + 10 * 10L);
        assertThat(broker3.getCredit()).isEqualTo(100_000_000L + 90 * 10L);
    }

    @Test
    void insert_buy_stop_order_but_does_not_match_even_after_update() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                10
        ));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(
                2,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                120,
                20,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                50
        ));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(120);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L - 120 * 20L);

    }

    @Test
    void insert_sell_stop_order_but_does_not_match_even_after_update() {
        security.getOrderBook().enqueue(
                new Order(1, security, BUY, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                SELL,
                120,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                5
        ));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(
                2,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                SELL,
                90,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                5
        ));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getSellQueue().get(0).getQuantity()).isEqualTo(90);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L);

    }

    @Test
    void insert_buy_stop_order_matching_after_update() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                BUY,
                10,
                10,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0
        ));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher).publish(any(OrderExecutedEvent.class));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                2,
                "TEST",
                3,
                LocalDateTime.now(),
                BUY,
                120,
                15,
                broker3.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                15
        ));

        verify(eventPublisher, times(2)).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(
                3,
                security.getIsin(),
                3,
                LocalDateTime.now(),
                BUY,
                120,
                20,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                5
        ));

        verify(eventPublisher, times(1)).publish(any(OrderUpdatedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderActivatedEvent.class));
        verify(eventPublisher, times(2)).publish(any(OrderExecutedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(0);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);

        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(30);
        assertThat(broker3.getCredit()).isEqualTo(100_000_000L - 90 * 10L - 30 * 20L);
    }

    @Test
    void insert_sell_stop_order_matching_after_update() {
        security.getOrderBook().enqueue(
                new Order(1, security, BUY, 100, 10, broker1, shareholder)
        );

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                1,
                security.getIsin(),
                2,
                LocalDateTime.now(),
                SELL,
                10,
                4,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0
        ));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher).publish(any(OrderExecutedEvent.class));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(
                2,
                "TEST",
                3,
                LocalDateTime.now(),
                SELL,
                120,
                8,
                broker3.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                4
        ));

        verify(eventPublisher, times(2)).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(
                3,
                security.getIsin(),
                3,
                LocalDateTime.now(),
                SELL,
                75,
                8,
                broker2.getBrokerId(),
                shareholder.getShareholderId(),
                0,
                0,
                15
        ));

        verify(eventPublisher, times(1)).publish(any(OrderUpdatedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderActivatedEvent.class));
        verify(eventPublisher, times(2)).publish(any(OrderExecutedEvent.class));
        assertThat(security.getOrderBook().getSellQueue()).hasSize(0);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);

        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(15);
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L + 10 * 10L);
        assertThat(broker3.getCredit()).isEqualTo(100_000_000L + 75 * 10L);
    }
}
