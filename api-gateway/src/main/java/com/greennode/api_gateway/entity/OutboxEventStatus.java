package com.greennode.api_gateway.entity;

/**
 * Represents the lifecycle status of an outbox event. Events transition through these states to
 * ensure at-least-once delivery semantics.
 */
public enum OutboxEventStatus {
    /** Event created but not yet processed */
    PENDING,

    /** Event currently being processed by the publisher */
    PROCESSING,

    /** Event successfully published to RabbitMQ */
    PUBLISHED,

    /** Event permanently failed after exceeding max retry attempts */
    FAILED
}
