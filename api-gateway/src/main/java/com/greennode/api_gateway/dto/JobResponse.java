package com.greennode.api_gateway.dto;

import com.greennode.api_gateway.entity.Job;
import com.greennode.api_gateway.entity.JobStatus;
import java.time.Instant;
import java.util.UUID;

public class JobResponse {

  private UUID id;
  private String taskType;
  private Integer complexity;
  private JobStatus status;
  private String result;
  private String failureMessage;
  private String clientRequestId;
  private UUID correlationId;
  private Instant createdAt;
  private Instant updatedAt;

  public static JobResponse from(Job job) {
    JobResponse response = new JobResponse();
    response.id = job.getId();
    response.taskType = job.getTaskType();
    response.complexity = job.getComplexity();
    response.status = job.getStatus();
    response.result = job.getResult();
    response.failureMessage = job.getFailureMessage();
    response.clientRequestId = job.getClientRequestId();
    response.correlationId = job.getCorrelationId();
    response.createdAt = job.getCreatedAt();
    response.updatedAt = job.getUpdatedAt();
    return response;
  }

  public UUID getId() {
    return id;
  }

  public String getTaskType() {
    return taskType;
  }

  public Integer getComplexity() {
    return complexity;
  }

  public JobStatus getStatus() {
    return status;
  }

  public String getResult() {
    return result;
  }

  public String getFailureMessage() {
    return failureMessage;
  }

  public String getClientRequestId() {
    return clientRequestId;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
