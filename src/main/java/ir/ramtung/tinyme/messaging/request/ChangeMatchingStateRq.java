package ir.ramtung.tinyme.messaging.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
public class ChangeMatchingStateRq extends Request {
    String securityIsin;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    MatchingState targetState;

    public ChangeMatchingStateRq(long requestId, LocalDateTime entryTime, String securityIsin, MatchingState targetState) {
        super(requestId, entryTime);
        this.securityIsin =securityIsin;
        this.targetState = targetState;
    }
}
