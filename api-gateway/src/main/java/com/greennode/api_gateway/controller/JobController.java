package com.greennode.api_gateway.controller;

import com.greennode.api_gateway.dto.JobRequest;
import com.greennode.api_gateway.dto.JobResponse;
import com.greennode.api_gateway.entity.JobStatus;
import com.greennode.api_gateway.service.JobService;
import com.greennode.api_gateway.service.JobService.SubmitJobResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

  private final JobService jobService;

  public JobController(JobService jobService) {
    this.jobService = jobService;
  }

  @PostMapping
  public ResponseEntity<JobResponse> createJob(@Valid @RequestBody JobRequest request) {
    SubmitJobResult result = jobService.submitJob(request);
    if (result.created()) {
      return ResponseEntity.accepted().body(result.response());
    }
    return ResponseEntity.ok(result.response());
  }

  @GetMapping
  public Page<JobResponse> listJobs(
      @RequestParam(required = false) JobStatus status,
      @RequestParam(required = false) String clientRequestId,
      Pageable pageable) {
    return jobService.listJobs(status, clientRequestId, pageable);
  }

  @GetMapping("/{jobId}")
  public ResponseEntity<JobResponse> getJob(@PathVariable UUID jobId) {
    return jobService
        .getJobStatus(jobId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/{jobId}/cancel")
  public ResponseEntity<JobResponse> cancelJob(@PathVariable UUID jobId) {
    return jobService
        .cancelJob(jobId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
