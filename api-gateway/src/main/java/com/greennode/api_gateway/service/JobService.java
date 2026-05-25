package com.greennode.api_gateway.service;

import com.greennode.api_gateway.dto.JobRequest;
import com.greennode.api_gateway.dto.JobResponse;
import com.greennode.api_gateway.entity.Job;
import com.greennode.api_gateway.entity.JobStatus;
import com.greennode.api_gateway.entity.OutboxEvent;
import com.greennode.api_gateway.entity.ProcessedEvent;
import com.greennode.api_gateway.messaging.EventEnvelope;
import com.greennode.api_gateway.messaging.EventTypes;
import com.greennode.api_gateway.messaging.JobCompletedPayload;
import com.greennode.api_gateway.messaging.JobFailedPayload;
import com.greennode.api_gateway.messaging.JobRequestedPayload;
import com.greennode.api_gateway.repository.JobRepository;
import com.greennode.api_gateway.repository.OutboxEventRepository;
import com.greennode.api_gateway.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobService {

  private static final Logger log = LoggerFactory.getLogger(JobService.class);
  private static final int SCHEMA_VERSION = 1;

  private final JobRepository jobRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ProcessedEventRepository processedEventRepository;
  private final JsonMapper jsonMapper;
  private final Tracer tracer;
  private final Counter jobsSubmitted;
  private final Counter jobsCompleted;
  private final Counter jobsFailed;
  private final Counter duplicateResultEvents;

  public JobService(
      JobRepository jobRepository,
      OutboxEventRepository outboxEventRepository,
      ProcessedEventRepository processedEventRepository,
      JsonMapper jsonMapper,
      Tracer tracer,
      MeterRegistry meterRegistry) {
    this.jobRepository = jobRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.processedEventRepository = processedEventRepository;
    this.jsonMapper = jsonMapper;
    this.tracer = tracer;
    this.jobsSubmitted = meterRegistry.counter("task_engine.jobs.submitted");
    this.jobsCompleted = meterRegistry.counter("task_engine.jobs.completed");
    this.jobsFailed = meterRegistry.counter("task_engine.jobs.failed");
    this.duplicateResultEvents = meterRegistry.counter("task_engine.events.duplicates");
  }

  @Transactional
  public JobResponse submitJob(JobRequest request) {
    if (request.getClientRequestId() != null && !request.getClientRequestId().isBlank()) {
      Optional<Job> existing = jobRepository.findByClientRequestId(request.getClientRequestId());
      if (existing.isPresent()) {
        return JobResponse.from(existing.get());
      }
    }

    UUID jobId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    Job job =
        new Job(
            jobId,
            request.getTaskType(),
            request.getComplexity(),
            JobStatus.QUEUED,
            normalizeClientRequestId(request.getClientRequestId()),
            correlationId);
    job = jobRepository.save(job);

    EventEnvelope<JobRequestedPayload> event =
        new EventEnvelope<>(
            eventId,
            EventTypes.JOB_REQUESTED,
            SCHEMA_VERSION,
            Instant.now().toString(),
            currentTraceparent(),
            correlationId,
            eventId,
            new JobRequestedPayload(jobId, request.getTaskType(), request.getComplexity()));

    outboxEventRepository.save(
        new OutboxEvent(
            eventId, jobId.toString(), "JOB", EventTypes.JOB_REQUESTED, serialize(event)));
    jobsSubmitted.increment();
    log.info("Queued job [{}] and outbox event [{}]", jobId, eventId);

    return JobResponse.from(job);
  }

  public Optional<JobResponse> getJobStatus(UUID jobId) {
    return jobRepository.findById(jobId).map(JobResponse::from);
  }

  @Transactional
  public boolean applyResultEvent(String rawEventJson) {
    EventEnvelope<?> envelope = deserializeEnvelope(rawEventJson);
    validateEnvelope(envelope);

    if (processedEventRepository.existsById(envelope.getEventId())) {
      duplicateResultEvents.increment();
      log.info("Ignoring duplicate event [{}]", envelope.getEventId());
      return true;
    }

    if (EventTypes.JOB_COMPLETED.equals(envelope.getEventType())) {
      JobCompletedPayload payload = convertPayload(envelope, JobCompletedPayload.class);
      completeJob(envelope, payload);
      return true;
    }

    if (EventTypes.JOB_FAILED.equals(envelope.getEventType())) {
      JobFailedPayload payload = convertPayload(envelope, JobFailedPayload.class);
      failJob(envelope, payload);
      return true;
    }

    throw new IllegalArgumentException("Unsupported event type: " + envelope.getEventType());
  }

  private void completeJob(EventEnvelope<?> envelope, JobCompletedPayload payload) {
    Job job =
        jobRepository
            .findById(payload.getJobId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown job: " + payload.getJobId()));
    recordProcessedEvent(envelope, payload.getJobId());

    if (isTerminal(job)) {
      duplicateResultEvents.increment();
      log.info("Ignoring result for terminal job [{}]", job.getId());
      return;
    }

    job.setStatus(JobStatus.COMPLETED);
    job.setFailureMessage(null);
    job.setResult(serialize(payload.getResult()));
    jobsCompleted.increment();
    log.info("Marked job [{}] COMPLETED from event [{}]", job.getId(), envelope.getEventId());
  }

  private void failJob(EventEnvelope<?> envelope, JobFailedPayload payload) {
    Job job =
        jobRepository
            .findById(payload.getJobId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown job: " + payload.getJobId()));
    recordProcessedEvent(envelope, payload.getJobId());

    if (isTerminal(job)) {
      duplicateResultEvents.increment();
      log.info("Ignoring failure for terminal job [{}]", job.getId());
      return;
    }

    job.setStatus(JobStatus.FAILED);
    job.setFailureMessage(payload.getErrorCode() + ": " + payload.getMessage());
    jobsFailed.increment();
    log.info("Marked job [{}] FAILED from event [{}]", job.getId(), envelope.getEventId());
  }

  private void recordProcessedEvent(EventEnvelope<?> envelope, UUID jobId) {
    try {
      processedEventRepository.save(
          new ProcessedEvent(envelope.getEventId(), envelope.getEventType(), jobId.toString()));
    } catch (DataIntegrityViolationException e) {
      duplicateResultEvents.increment();
      log.info("Event [{}] was already processed concurrently", envelope.getEventId());
    }
  }

  private EventEnvelope<?> deserializeEnvelope(String rawEventJson) {
    try {
      return jsonMapper.readValue(rawEventJson, EventEnvelope.class);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Message is not a valid event envelope", e);
    }
  }

  private <T> T convertPayload(EventEnvelope<?> envelope, Class<T> payloadType) {
    try {
      String payloadJson = jsonMapper.writeValueAsString(envelope.getPayload());
      return jsonMapper.readValue(payloadJson, payloadType);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(
          "Event payload does not match " + payloadType.getName(), e);
    }
  }

  private void validateEnvelope(EventEnvelope<?> envelope) {
    if (envelope.getEventId() == null
        || envelope.getEventType() == null
        || envelope.getSchemaVersion() == null
        || envelope.getSchemaVersion() != SCHEMA_VERSION
        || envelope.getOccurredAt() == null
        || envelope.getTraceparent() == null
        || envelope.getTraceparent().isBlank()
        || envelope.getCorrelationId() == null
        || envelope.getCausationId() == null
        || envelope.getPayload() == null) {
      throw new IllegalArgumentException("Event envelope is missing required fields");
    }
  }

  private boolean isTerminal(Job job) {
    return job.getStatus() == JobStatus.COMPLETED
        || job.getStatus() == JobStatus.FAILED
        || job.getStatus() == JobStatus.CANCELLED;
  }

  private String serialize(Object value) {
    try {
      return jsonMapper.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalStateException("Failed to serialize JSON value", e);
    }
  }

  private String normalizeClientRequestId(String clientRequestId) {
    return clientRequestId == null || clientRequestId.isBlank() ? null : clientRequestId;
  }

  private String currentTraceparent() {
    if (tracer.currentSpan() == null) {
      return "00-00000000000000000000000000000000-0000000000000000-00";
    }
    return "00-"
        + tracer.currentSpan().context().traceId()
        + "-"
        + tracer.currentSpan().context().spanId()
        + "-01";
  }
}
