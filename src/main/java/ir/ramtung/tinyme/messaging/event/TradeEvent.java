package ir.ramtung.tinyme.messaging.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class TradeEvent extends Event{
    String securityIsin;
    int price;
    int quantity;
    long buyId;
    long sellId;
}
