package com.greennode.api_gateway.messaging;

import java.util.UUID;

public class EventEnvelope<T> {

  private UUID eventId;
  private String eventType;
  private Integer schemaVersion;
  private String occurredAt;
  private String traceparent;
  private UUID correlationId;
  private UUID causationId;
  private T payload;

  public EventEnvelope() {}

  public EventEnvelope(
      UUID eventId,
      String eventType,
      Integer schemaVersion,
      String occurredAt,
      String traceparent,
      UUID correlationId,
      UUID causationId,
      T payload) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.schemaVersion = schemaVersion;
    this.occurredAt = occurredAt;
    this.traceparent = traceparent;
    this.correlationId = correlationId;
    this.causationId = causationId;
    this.payload = payload;
  }

  public UUID getEventId() {
    return eventId;
  }

  public void setEventId(UUID eventId) {
    this.eventId = eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public Integer getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Integer schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(String occurredAt) {
    this.occurredAt = occurredAt;
  }

  public String getTraceparent() {
    return traceparent;
  }

  public void setTraceparent(String traceparent) {
    this.traceparent = traceparent;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(UUID correlationId) {
    this.correlationId = correlationId;
  }

  public UUID getCausationId() {
    return causationId;
  }

  public void setCausationId(UUID causationId) {
    this.causationId = causationId;
  }

  public T getPayload() {
    return payload;
  }

  public void setPayload(T payload) {
    this.payload = payload;
  }
}
