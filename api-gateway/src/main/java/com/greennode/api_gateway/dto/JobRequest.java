package com.greennode.api_gateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for validating incoming HTTP POST requests. Conforms to the security constraint in AGENTS.md:
 * "Validate all incoming HTTP payloads."
 */
public class JobRequest {

    @NotBlank(message = "Task type cannot be empty") private String taskType;

    @NotNull(message = "Complexity must be provided") @Min(value = 1, message = "Complexity must be at least 1") @Max(value = 10, message = "Complexity must not exceed 10") private Integer complexity;

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
