package ir.ramtung.tinyme.messaging.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import ir.ramtung.tinyme.domain.entity.Side;
import lombok.*;

import java.time.LocalDateTime;

@Value
@Builder
public class EnterOrderRq {
    OrderEntryType requestType;
    long requestId;
    String securityIsin;
    long orderId;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @Builder.Default
    LocalDateTime entryTime = LocalDateTime.now();
    Side side;
    int quantity;
    int price;
    long brokerId;
    long shareholderId;
    @Builder.Default
    int peakSize = 0;
    @Builder.Default
    int minimumExecutionQuantity = 0;
    @Builder.Default
    int stopPrice = 0;

    @Deprecated
    public static EnterOrderRq createNewOrderRq(long requestId, String securityIsin, long orderId, LocalDateTime entryTime, Side side, int quantity, int price, long brokerId, long shareholderId, int peakSize, int minimumExecutionQuantity, int stopPrice) {
        return builderWithFilledEssentialFields(requestId, securityIsin, orderId, entryTime, side, quantity, price, brokerId, shareholderId)
                .requestType(OrderEntryType.NEW_ORDER)
                .peakSize(peakSize)
                .minimumExecutionQuantity(minimumExecutionQuantity)
                .stopPrice(stopPrice)
                .build();
    }

    @Deprecated
    public static EnterOrderRq createNewOrderRq(long requestId, String securityIsin, long orderId, LocalDateTime entryTime, Side side, int quantity, int price, long brokerId, long shareholderId, int peakSize, int minimumExecutionQuantity) {
        return builderWithFilledEssentialFields(requestId, securityIsin, orderId, entryTime, side, quantity, price, brokerId, shareholderId)
                .requestType(OrderEntryType.NEW_ORDER)
                .peakSize(peakSize)
                .minimumExecutionQuantity(minimumExecutionQuantity)
                .build();
    }

    @Deprecated
    public static EnterOrderRq createNewOrderRq(long requestId, String securityIsin, long orderId, LocalDateTime entryTime, Side side, int quantity, int price, long brokerId, long shareholderId, int peakSize) {
        return builderWithFilledEssentialFields(requestId, securityIsin, orderId, entryTime, side, quantity, price, brokerId, shareholderId)
                .requestType(OrderEntryType.NEW_ORDER)
                .peakSize(peakSize)
                .build();
    }

    @Deprecated
    public static EnterOrderRq createUpdateOrderRq(long requestId, String securityIsin, long orderId, LocalDateTime entryTime, Side side, int quantity, int price, long brokerId, long shareholderId, int peakSize, int minimumExecutionQuantity, int stopPrice) {
        return builderWithFilledEssentialFields(requestId, securityIsin, orderId, entryTime, side, quantity, price, brokerId, shareholderId)
                .requestType(OrderEntryType.UPDATE_ORDER)
                .peakSize(peakSize)
                .minimumExecutionQuantity(minimumExecutionQuantity)
                .stopPrice(stopPrice)
                .build();
    }

    @Deprecated
    public static EnterOrderRq createUpdateOrderRq(long requestId, String securityIsin, long orderId, LocalDateTime entryTime, Side side, int quantity, int price, long brokerId, long shareholderId, int peakSize, int minimumExecutionQuantity) {
        return builderWithFilledEssentialFields(requestId, securityIsin, orderId, entryTime, side, quantity, price, brokerId, shareholderId)
                .requestType(OrderEntryType.UPDATE_ORDER)
                .peakSize(peakSize)
                .minimumExecutionQuantity(minimumExecutionQuantity)
                .build();
    }

    @Deprecated
    public static EnterOrderRq createUpdateOrderRq(long requestId, String securityIsin, long orderId, LocalDateTime entryTime, Side side, int quantity, int price, long brokerId, long shareholderId, int peakSize) {
        return builderWithFilledEssentialFields(requestId, securityIsin, orderId, entryTime, side, quantity, price, brokerId, shareholderId)
                .requestType(OrderEntryType.UPDATE_ORDER)
                .peakSize(peakSize)
                .build();
    }

    @Deprecated
    private static EnterOrderRq.EnterOrderRqBuilder builderWithFilledEssentialFields(long requestId, String securityIsin, long orderId, LocalDateTime entryTime, Side side, int quantity, int price, long brokerId, long shareholderId) {
        return EnterOrderRq.builder()
                .requestId(requestId)
                .securityIsin(securityIsin)
                .orderId(orderId)
                .entryTime(entryTime)
                .side(side)
                .quantity(quantity)
                .price(price)
                .brokerId(brokerId)
                .shareholderId(shareholderId);
    }
}
