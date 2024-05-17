package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.Broker;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Shareholder;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.order.StopOrder;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.domain.service.SecurityConfigurationHandler;
import ir.ramtung.tinyme.domain.service.matcher.AuctionMatcher;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
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
    SecurityConfigurationHandler securityConfigurationHandler;
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
    Broker broker2;
    Broker broker3;
    Shareholder shareholder;

    @BeforeEach
    void setup() {
        security = Security.builder().matcher(auctionMatcher).build();
        securityRepository.addSecurity(security);

        broker1 = Broker.builder().brokerId(1).credit(1_000_000L).build();
        broker2 = Broker.builder().brokerId(2).credit(1_000_000L).build();
        broker3 = Broker.builder().brokerId(3).credit(1_000_000L).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);

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

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 60*1545 - 12*1545);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(2);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(3);
    }

    @Test
    void update_orders_must_just_update_accumulated_order() {
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

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 60*1545);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(2);

        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 3).getPrice()).isEqualTo(1500);
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 3).getQuantity()).isEqualTo(40);
    }

    @Test
    void update_buy_orders_must_also_update_credit() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 20, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(4, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 40, 1500, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher, times(3)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderUpdatedEvent.class));

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 40*1500);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(2);
    }

    @Test
    void delete_orders_must_just_delete_accumulated_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 20, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(4, security.getIsin(), Side.SELL, 3, LocalDateTime.now()));

        verify(eventPublisher, times(3)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderDeletedEvent.class));

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 60*1545);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);
    }

    @Test
    void delete_buy_orders_must_also_rollback_credit() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 20, 1545, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(4, security.getIsin(), Side.BUY, 1, LocalDateTime.now()));

        verify(eventPublisher, times(3)).publish(any(OrderAcceptedEvent.class));
        verify(eventPublisher, times(1)).publish(any(OrderDeletedEvent.class));

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L);
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(2);
    }

    @Test
    void opening_price_must_be_right() {
        security.getOrderBook().setLastTransactionPrice(1430);
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

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 60*1300 - 12*1450);
        assertThat(security.getOrderBook().calculateOpeningState().price()).isEqualTo(1430);
        assertThat(security.getOrderBook().calculateOpeningState().tradableQuantity()).isEqualTo(12);
    }

    @Test
    void opening_price_events_must_get_published() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1400, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher, times(2)).publish(any(OpeningPriceEvent.class));
    }

    @Test
    void auction_execution_with_no_trade() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1400, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        securityConfigurationHandler.handleMatchingStateRq(new ChangeMatchingStateRq(3, LocalDateTime.now(), security.getIsin(), MatchingState.AUCTION));
        verify(eventPublisher, times(1)).publish(any(OrderRejectedEvent.class));
        verify(eventPublisher, times(0)).publish(any(TradeEvent.class));
    }

    @Test
    void auction_execution_normal_with_buy_orders_to_finish() {
        security.getOrderBook().setLastTransactionPrice(1400);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 60, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1250, broker2.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 15, 1290, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        securityConfigurationHandler.handleMatchingStateRq(new ChangeMatchingStateRq(5, LocalDateTime.now(), security.getIsin(), MatchingState.AUCTION));

        assertThat(security.getOrderBook().getLastTransactionPrice()).isEqualTo(1300);

        verify(eventPublisher, times(1)).publish(any(OrderExecutedEvent.class));
        verify(eventPublisher, times(2)).publish(any(TradeEvent.class));

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 60*1300);
        assertThat(broker2.getCredit()).isEqualTo(1_000_000L + 50*1300);
        assertThat(broker3.getCredit()).isEqualTo(1_000_000L + 10*1300);
    }

    @Test
    void auction_execution_normal_with_sell_orders_to_finish() {
        security.getOrderBook().setLastTransactionPrice(1000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 80, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1250, broker2.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 15, 1290, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        securityConfigurationHandler.handleMatchingStateRq(new ChangeMatchingStateRq(5, LocalDateTime.now(), security.getIsin(), MatchingState.AUCTION));

        assertThat(security.getOrderBook().getLastTransactionPrice()).isEqualTo(1290);

        verify(eventPublisher, times(1)).publish(any(OrderExecutedEvent.class));
        verify(eventPublisher, times(2)).publish(any(TradeEvent.class));

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 65*1290 - 15*1300);
        assertThat(broker2.getCredit()).isEqualTo(1_000_000L + 50*1290);
        assertThat(broker3.getCredit()).isEqualTo(1_000_000L + 15*1290);
    }

    @Test
    void auction_execution_iceberg_order() {
        security.getOrderBook().setLastTransactionPrice(1000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 80, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 5));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1250, broker2.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 15, 1290, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        securityConfigurationHandler.handleMatchingStateRq(new ChangeMatchingStateRq(5, LocalDateTime.now(), security.getIsin(), MatchingState.AUCTION));

        assertThat(security.getOrderBook().getLastTransactionPrice()).isEqualTo(1290);

        verify(eventPublisher, times(1)).publish(any(OrderExecutedEvent.class));
        verify(eventPublisher, times(2)).publish(any(TradeEvent.class));

        assertThat(broker1.getCredit()).isEqualTo(1_000_000L - 65*1290 - 15*1300);
        assertThat(broker2.getCredit()).isEqualTo(1_000_000L + 50*1290);
        assertThat(broker3.getCredit()).isEqualTo(1_000_000L + 15*1290);
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 1).getQuantity()).isEqualTo(5);
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 1).getTotalQuantity()).isEqualTo(15);
    }

    @Test
    void last_transaction_price_must_be_updated_after_auction() {
        security.getOrderBook().setLastTransactionPrice(1000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 80, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1250, broker2.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 15, 1290, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        securityConfigurationHandler.handleMatchingStateRq(new ChangeMatchingStateRq(5, LocalDateTime.now(), security.getIsin(), MatchingState.AUCTION));

        assertThat(security.getOrderBook().getLastTransactionPrice()).isEqualTo(1290);
    }

    @Test
    void stop_order_must_be_activated_after_auction() {
        security.getOrderBook().enqueue(
                new StopOrder(0, security, Side.BUY, 40, 1400, broker3, shareholder, LocalDateTime.now(), 1200)
        );
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 0).isActive()).isEqualTo(false);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 1, LocalDateTime.now(),
                Side.BUY, 80, 1300, broker1.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 2, LocalDateTime.now(),
                Side.SELL, 50, 1250, broker2.getBrokerId(), shareholder.getShareholderId(), 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, security.getIsin(), 3, LocalDateTime.now(),
                Side.SELL, 15, 1290, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        securityConfigurationHandler.handleMatchingStateRq(new ChangeMatchingStateRq(5, LocalDateTime.now(), security.getIsin(), MatchingState.AUCTION));

        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 0).isActive()).isEqualTo(true);
        verify(eventPublisher).publish(any(OrderActivatedEvent.class));
    }
}

