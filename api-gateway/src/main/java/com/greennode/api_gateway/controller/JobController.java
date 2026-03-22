package com.greennode.api_gateway.controller;

import com.greennode.api_gateway.dto.JobRequest;
import com.greennode.api_gateway.entity.Job;
import com.greennode.api_gateway.service.JobService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API Endpoints. */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * POST /api/v1/jobs @Valid triggers the jakarta.validation constraints defined in our
     * JobRequest DTO.
     */
    @PostMapping
    public ResponseEntity<Job> createJob(@Valid @RequestBody JobRequest request) {
        Job job = jobService.submitJob(request);
        // Return 202 Accepted because the task is queued, not fully processed yet.
        return ResponseEntity.accepted().body(job);
    }

    /** GET /api/v1/jobs/{jobId} */
    @GetMapping("/{jobId}")
    public ResponseEntity<Job> getJob(@PathVariable UUID jobId) {
        return jobService
                .getJobStatus(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
