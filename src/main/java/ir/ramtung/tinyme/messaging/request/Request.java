package ir.ramtung.tinyme.messaging.request;

import java.time.LocalDateTime;

public interface Request {
    long getRequestId();
    LocalDateTime getEntryTime();
}
