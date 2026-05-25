package com.greennode.api_gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs")
public class Job {

  @Id private UUID id;

  @Column(name = "task_type", nullable = false)
  private String taskType;

  @Column(nullable = false)
  private Integer complexity;

  @Enumerated(EnumType.STRING)
  @Column(columnDefinition = "job_status", nullable = false)
  @JdbcType(PostgreSQLEnumJdbcType.class)
  private JobStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String result;

  @Column(name = "failure_message")
  private String failureMessage;

  @Column(name = "client_request_id", length = 128, unique = true)
  private String clientRequestId;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private Instant updatedAt;

  protected Job() {}

  public Job(
      UUID id,
      String taskType,
      Integer complexity,
      JobStatus status,
      String clientRequestId,
      UUID correlationId) {
    this.id = id;
    this.taskType = taskType;
    this.complexity = complexity;
    this.status = status;
    this.clientRequestId = clientRequestId;
    this.correlationId = correlationId;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getTaskType() {
    return taskType;
  }

  public void setTaskType(String taskType) {
    this.taskType = taskType;
  }

  public Integer getComplexity() {
    return complexity;
  }

  public void setComplexity(Integer complexity) {
    this.complexity = complexity;
  }

  public JobStatus getStatus() {
    return status;
  }

  public void setStatus(JobStatus status) {
    this.status = status;
  }

  public String getResult() {
    return result;
  }

  public void setResult(String result) {
    this.result = result;
  }

  public String getFailureMessage() {
    return failureMessage;
  }

  public void setFailureMessage(String failureMessage) {
    this.failureMessage = failureMessage;
  }

  public String getClientRequestId() {
    return clientRequestId;
  }

  public void setClientRequestId(String clientRequestId) {
    this.clientRequestId = clientRequestId;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(UUID correlationId) {
    this.correlationId = correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
