package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.entity.order.Order;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;

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
    private Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().isin("TEST").build();
        broker1 = Broker.builder().credit(100_000_000L).build();
        broker2 = Broker.builder().credit(100_000_000L).build();
        broker3 = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 1_000);
    }

    @Test
    void insert_buy_stop_order_but_do_not_match() {
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
        assertThat(result.trades()).hasSize(0);
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
        assertThat(result.trades()).hasSize(1);
        assertThat(security.getOrderBook().getSellQueue()).hasSize(0);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(30);
        assertThat(broker3.getCredit()).isEqualTo(100_000_000L - 90 * 10L - 30 * 15L);
    }
}
