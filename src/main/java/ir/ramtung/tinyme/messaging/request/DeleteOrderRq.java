package ir.ramtung.tinyme.messaging.request;

import ir.ramtung.tinyme.domain.entity.Side;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = false)
@Data
public class DeleteOrderRq extends Request {
    private long requestId;
    private String securityIsin;
    private Side side;
    private long orderId;

    public DeleteOrderRq(long requestId, String securityIsin, Side side, long orderId, LocalDateTime entryTime) {
        super(entryTime);
        this.requestId = requestId;
        this.securityIsin = securityIsin;
        this.side = side;
        this.orderId = orderId;
    }

    public DeleteOrderRq(long requestId, String securityIsin, Side side, long orderId) {
        this(requestId, securityIsin, side, orderId, LocalDateTime.now());
    }
}
