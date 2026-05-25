package com.greennode.api_gateway.messaging;

import java.util.UUID;

public class JobRequestedPayload {
  private UUID jobId;
  private String taskType;
  private Integer complexity;

  public JobRequestedPayload() {}

  public JobRequestedPayload(UUID jobId, String taskType, Integer complexity) {
    this.jobId = jobId;
    this.taskType = taskType;
    this.complexity = complexity;
  }

  public UUID getJobId() {
    return jobId;
  }

  public void setJobId(UUID jobId) {
    this.jobId = jobId;
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
}
