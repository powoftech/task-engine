package com.greennode.api_gateway.messaging;

import java.util.Map;
import java.util.UUID;

public class JobCompletedPayload {
  private UUID jobId;
  private Integer processedInSeconds;
  private Map<String, Object> result;

  public UUID getJobId() {
    return jobId;
  }

  public void setJobId(UUID jobId) {
    this.jobId = jobId;
  }

  public Integer getProcessedInSeconds() {
    return processedInSeconds;
  }

  public void setProcessedInSeconds(Integer processedInSeconds) {
    this.processedInSeconds = processedInSeconds;
  }

  public Map<String, Object> getResult() {
    return result;
  }

  public void setResult(Map<String, Object> result) {
    this.result = result;
  }
}
