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
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class StopOrderHandlerTest {
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().isin("TEST").build();
        broker = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 1_000);
    }

    @Test
    void insert_buy_stop_order_but_do_not_match() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker, shareholder)
        );

        MatchResult result = security.newOrder(EnterOrderRq.createNewOrderRq(
                1,
                "TEST",
                2,
                LocalDateTime.now(),
                BUY,
                120,
                10,
                2,
                shareholder.getShareholderId(),
                0,
                0,
                10
        ), broker, shareholder, matcher);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(result.trades()).hasSize(0);
        assertThat(security.getOrderBook().getSellQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(120);

    }

    @Test
    void insert_buy_stop_order_and_match() {
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker, shareholder)
        );

        security.newOrder(EnterOrderRq.createNewOrderRq(
                1,
                "TEST",
                2,
                LocalDateTime.now(),
                BUY,
                10,
                10,
                2,
                shareholder.getShareholderId(),
                0
        ), broker, shareholder, matcher);

        MatchResult result = security.newOrder(EnterOrderRq.createNewOrderRq(
                1,
                "TEST",
                2,
                LocalDateTime.now(),
                BUY,
                120,
                15,
                2,
                shareholder.getShareholderId(),
                0,
                0,
                10
        ), broker, shareholder, matcher);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(result.trades()).hasSize(1);
        assertThat(security.getOrderBook().getSellQueue()).hasSize(0);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(30);

    }
}
