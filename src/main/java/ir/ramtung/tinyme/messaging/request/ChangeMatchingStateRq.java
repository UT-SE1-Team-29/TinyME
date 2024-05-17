package ir.ramtung.tinyme.messaging.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
public class ChangeMatchingStateRq extends Request {
    String SecurityIsin;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    MatchingState targetState;
}
