package ir.ramtung.tinyme.messaging.exception;

import lombok.ToString;

import java.util.List;

import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.request.OrderManipulationRequest;

@ToString
public class InvalidRequestException extends Exception {
    private final List<String> reasons;

    public InvalidRequestException(List<String> reasons) {
        this.reasons = reasons;
    }

    public InvalidRequestException(String reason) {
        this.reasons = List.of(reason);
    }

    public void publishEvent(EventPublisher eventPublisher, OrderManipulationRequest request) {
        eventPublisher.publish(new OrderRejectedEvent(request.getRequestId(), request.getOrderId(), reasons));
    }
}
