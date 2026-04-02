package com.greennode.api_gateway.dto;

import java.util.UUID;

public class JobMessage {

    private UUID jobId;
    private String taskType;
    private Integer complexity;
    private String traceId;

    public JobMessage() {}

    public JobMessage(UUID jobId, String taskType, Integer complexity, String traceId) {
        this.jobId = jobId;
        this.taskType = taskType;
        this.complexity = complexity;
        this.traceId = traceId;
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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
