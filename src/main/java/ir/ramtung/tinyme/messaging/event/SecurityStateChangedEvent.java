package ir.ramtung.tinyme.messaging.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class SecurityStateChangedEvent extends Event{
    String securityIsin;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    MatchingState state;
}
