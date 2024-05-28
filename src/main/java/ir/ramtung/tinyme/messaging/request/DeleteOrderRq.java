package ir.ramtung.tinyme.messaging.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import ir.ramtung.tinyme.domain.entity.Side;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
@AllArgsConstructor(onConstructor = @__({@Deprecated}))
public class DeleteOrderRq {
    long requestId;
    String securityIsin;
    Side side;
    long orderId;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @Builder.Default
    LocalDateTime entryTime = LocalDateTime.now();

    @Deprecated
    public DeleteOrderRq(long requestId, String securityIsin, Side side, long orderId) {
        this(requestId, securityIsin, side, orderId, LocalDateTime.now());
    }
}
