package com.greennode.api_gateway.entity;

/**
 * Enum representing the possible states of a job in the system. Must match the PostgreSQL
 * job_status enum type.
 */
public enum JobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
