package com.greennode.api_gateway.dto;

import java.util.UUID;

/**
 * The specific payload structure that will be serialized to JSON and pushed into the RabbitMQ
 * queue. The Go workers will deserialize this.
 */
public class JobMessage {

    private UUID jobId;
    private String taskType;
    private Integer complexity;

    public JobMessage() {}

    public JobMessage(UUID jobId, String taskType, Integer complexity) {
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
