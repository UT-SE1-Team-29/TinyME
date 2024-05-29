package ir.ramtung.tinyme.messaging.request;

public record Extensions(
    int peakSize,
    int minimumExecutionQuantity,
    int stopPrice
) {}
