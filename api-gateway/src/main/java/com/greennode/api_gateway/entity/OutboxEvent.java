package com.greennode.api_gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Represents a message in the transactional outbox. Each event corresponds to a message that will
 * be captured by Debezium CDC and published to RabbitMQ. By storing these in the database alongside
 * the Job entity, we achieve atomicity and prevent race conditions.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 255)
    private String aggregateType;

    @Column(name = "type", nullable = false, length = 255)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    // Protected no-args constructor required by JPA
    protected OutboxEvent() {}

    public OutboxEvent(String aggregateId, String aggregateType, String type, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.type = type;
        this.payload = payload;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getType() {
        return type;
    }

    public void setType(String eventType) {
        this.type = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
