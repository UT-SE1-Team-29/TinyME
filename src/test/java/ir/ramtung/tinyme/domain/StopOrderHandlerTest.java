package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.Broker;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Shareholder;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
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
    void setupOrderBook() {
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
    void insert_buy_stop_order_but_do_not_match() {
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
                "TEST",
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
    void validate_order_input() {
        EnterOrderRq request = EnterOrderRq.createNewOrderRq(
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
        );

        MatchResult result = security.newOrder(request, broker2, shareholder, matcher);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.INVALID);
    }

    @Test
    void activate_stop_order() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        MatchResult result = security.newOrder(EnterOrderRq.createNewOrderRq(
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
        ), broker2, shareholder, matcher);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }

    @Test
    void update_stop_order() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        MatchResult result = security.newOrder(EnterOrderRq.createNewOrderRq(
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
        ), broker2, shareholder, matcher);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }

    @Test
    void accept_order() {

        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );

        MatchResult result = security.newOrder(EnterOrderRq.createNewOrderRq(
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
        ), broker2, shareholder, matcher);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }

    @Test
    void execute_order() {

        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker1, shareholder)
        );
        security.newOrder(EnterOrderRq.createNewOrderRq(
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
        ), broker2, shareholder, matcher);

        MatchResult result = security.newOrder(EnterOrderRq.createNewOrderRq(
                1,
                "TEST",
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
        ), broker3, shareholder, matcher);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }
}
