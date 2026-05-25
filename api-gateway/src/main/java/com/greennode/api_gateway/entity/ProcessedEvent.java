package com.greennode.api_gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "event_type", nullable = false, length = 255)
  private String eventType;

  @Column(name = "aggregate_id", nullable = false, length = 255)
  private String aggregateId;

  @CreationTimestamp
  @Column(name = "processed_at", updatable = false)
  private Instant processedAt;

  protected ProcessedEvent() {}

  public ProcessedEvent(UUID eventId, String eventType, String aggregateId) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.aggregateId = aggregateId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getAggregateId() {
    return aggregateId;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
