package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.Broker;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Shareholder;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.domain.service.matcher.AuctionMatcher;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderDeletedEvent;
import ir.ramtung.tinyme.messaging.event.OrderUpdatedEvent;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class AuctionMatchingTest {
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
    @Autowired
    AuctionMatcher auctionMatcher;

    Security security;
    Broker broker1;
    Shareholder shareholder;

    @BeforeEach
    void setup() {
        security = Security.builder().matcher(auctionMatcher).build();
        securityRepository.addSecurity(security);

        broker1 = Broker.builder().brokerId(1).build();
        brokerRepository.addBroker(broker1);

        shareholder = Shareholder.builder().shareholderId(1).build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);
    }

    @AfterEach
    void teardown() {
        securityRepository.clear();
    }

    @Test
    void new_orders_must_be_accumulated() {
        broker1.increaseCreditBy(100_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 20, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, security.getIsin(), 4, LocalDateTime.now(),
                Side.BUY, 12, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, security.getIsin(), 5, LocalDateTime.now(),
                Side.SELL, 60, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher, times(5)).publish(any(OrderAcceptedEvent.class));

        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(2);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(3);
    }

    @Test
    void update_orders_must_just_update_accumulated_order() {
        broker1.increaseCreditBy(100_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 20, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(4, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 40, 1500, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher, times(3)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderUpdatedEvent.class));

        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(2);
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 3).getPrice()).isEqualTo(1500);
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 3).getQuantity()).isEqualTo(40);
    }

    @Test
    void delete_orders_must_just_delete_accumulated_order() {
        broker1.increaseCreditBy(100_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 20, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(4, security.getIsin(), Side.SELL, 3, LocalDateTime.now()));

        verify(eventPublisher, times(3)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderDeletedEvent.class));

        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);
    }

    @Test
    void opening_price_must_be_right() {
        security.getOrderBook().setLastTransactionPrice(1430);
        broker1.increaseCreditBy(100_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1400, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 20, 1500, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, security.getIsin(), 4, LocalDateTime.now(),
                Side.BUY, 12, 1450, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, security.getIsin(), 5, LocalDateTime.now(),
                Side.SELL, 60, 1420, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(security.getOrderBook().calculateOpeningPrice()).isEqualTo(1430);
    }
}

