package com.greennode.api_gateway.messaging;

import java.util.UUID;

public class JobProcessingPayload {
  private UUID jobId;
  private String startedAt;

  public UUID getJobId() {
    return jobId;
  }

  public void setJobId(UUID jobId) {
    this.jobId = jobId;
  }

  public String getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(String startedAt) {
    this.startedAt = startedAt;
  }
}
