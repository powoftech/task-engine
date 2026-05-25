package com.greennode.api_gateway.service;

import com.greennode.api_gateway.dto.JobRequest;
import com.greennode.api_gateway.dto.JobResponse;
import com.greennode.api_gateway.entity.Job;
import com.greennode.api_gateway.entity.JobStatus;
import com.greennode.api_gateway.entity.OutboxEvent;
import com.greennode.api_gateway.entity.ProcessedEvent;
import com.greennode.api_gateway.messaging.EventContractValidator;
import com.greennode.api_gateway.messaging.EventEnvelope;
import com.greennode.api_gateway.messaging.EventTypes;
import com.greennode.api_gateway.messaging.JobCompletedPayload;
import com.greennode.api_gateway.messaging.JobFailedPayload;
import com.greennode.api_gateway.messaging.JobProcessingPayload;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
  private final EventContractValidator contractValidator;
  private final JsonMapper jsonMapper;
  private final Tracer tracer;
  private final Counter jobsSubmitted;
  private final Counter jobsCompleted;
  private final Counter jobsFailed;
  private final Counter jobsCancelled;
  private final Counter duplicateResultEvents;
  private final Counter ignoredTerminalEvents;
  private final Counter stateTransitionConflicts;

  public JobService(
      JobRepository jobRepository,
      OutboxEventRepository outboxEventRepository,
      ProcessedEventRepository processedEventRepository,
      EventContractValidator contractValidator,
      JsonMapper jsonMapper,
      Tracer tracer,
      MeterRegistry meterRegistry) {
    this.jobRepository = jobRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.processedEventRepository = processedEventRepository;
    this.contractValidator = contractValidator;
    this.jsonMapper = jsonMapper;
    this.tracer = tracer;
    this.jobsSubmitted = meterRegistry.counter("task_engine.jobs.submitted");
    this.jobsCompleted = meterRegistry.counter("task_engine.jobs.completed");
    this.jobsFailed = meterRegistry.counter("task_engine.jobs.failed");
    this.jobsCancelled = meterRegistry.counter("task_engine.jobs.cancelled");
    this.duplicateResultEvents = meterRegistry.counter("task_engine.events.duplicates");
    this.ignoredTerminalEvents = meterRegistry.counter("task_engine.events.ignored_terminal");
    this.stateTransitionConflicts = meterRegistry.counter("task_engine.jobs.transition_conflicts");
  }

  @Transactional(noRollbackFor = DataIntegrityViolationException.class)
  public SubmitJobResult submitJob(JobRequest request) {
    String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
    if (clientRequestId != null) {
      Optional<Job> existing = jobRepository.findByClientRequestId(clientRequestId);
      if (existing.isPresent()) {
        return SubmitJobResult.duplicate(JobResponse.from(existing.get()));
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
            clientRequestId,
            correlationId);
    try {
      job = jobRepository.saveAndFlush(job);
    } catch (DataIntegrityViolationException e) {
      if (clientRequestId != null) {
        return SubmitJobResult.duplicate(
            jobRepository
                .findByClientRequestId(clientRequestId)
                .map(JobResponse::from)
                .orElseThrow(() -> e));
      }
      throw e;
    }

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
    String rawEvent = serialize(event);
    contractValidator.validate(rawEvent);

    outboxEventRepository.save(
        new OutboxEvent(eventId, jobId.toString(), "JOB", EventTypes.JOB_REQUESTED, rawEvent));
    jobsSubmitted.increment();
    log.info("Queued job [{}] and outbox event [{}]", jobId, eventId);

    return SubmitJobResult.created(JobResponse.from(job));
  }

  public Page<JobResponse> listJobs(JobStatus status, String clientRequestId, Pageable pageable) {
    String normalizedClientRequestId = normalizeClientRequestId(clientRequestId);
    if (status != null && normalizedClientRequestId != null) {
      return jobRepository
          .findByStatusAndClientRequestId(status, normalizedClientRequestId, pageable)
          .map(JobResponse::from);
    }
    if (status != null) {
      return jobRepository.findByStatus(status, pageable).map(JobResponse::from);
    }
    if (normalizedClientRequestId != null) {
      return jobRepository
          .findByClientRequestId(normalizedClientRequestId, pageable)
          .map(JobResponse::from);
    }
    return jobRepository.findAll(pageable).map(JobResponse::from);
  }

  public Optional<JobResponse> getJobStatus(UUID jobId) {
    return jobRepository.findById(jobId).map(JobResponse::from);
  }

  @Transactional
  public Optional<JobResponse> cancelJob(UUID jobId) {
    if (jobRepository.markCancelled(jobId) == 1) {
      jobsCancelled.increment();
    }
    return jobRepository.findById(jobId).map(JobResponse::from);
  }

  @Transactional
  public boolean applyWorkerEvent(String rawEventJson) {
    contractValidator.validate(rawEventJson);
    EventEnvelope<?> envelope = deserializeEnvelope(rawEventJson);
    validateEnvelope(envelope);

    if (processedEventRepository.existsById(envelope.getEventId())) {
      duplicateResultEvents.increment();
      log.info("Ignoring duplicate event [{}]", envelope.getEventId());
      return true;
    }

    if (EventTypes.JOB_PROCESSING.equals(envelope.getEventType())) {
      JobProcessingPayload payload = convertPayload(envelope, JobProcessingPayload.class);
      markProcessing(envelope, payload);
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

  private void markProcessing(EventEnvelope<?> envelope, JobProcessingPayload payload) {
    Job job = requireJob(payload.getJobId());
    if (!recordProcessedEvent(envelope, payload.getJobId())) {
      return;
    }
    int updated = jobRepository.markProcessing(payload.getJobId());
    if (updated == 1) {
      log.info(
          "Marked job [{}] PROCESSING from event [{}]", payload.getJobId(), envelope.getEventId());
      return;
    }
    ignoreOrRecordConflict(job);
  }

  private void completeJob(EventEnvelope<?> envelope, JobCompletedPayload payload) {
    Job job = requireJob(payload.getJobId());
    if (!recordProcessedEvent(envelope, payload.getJobId())) {
      return;
    }
    int updated = jobRepository.markCompleted(payload.getJobId(), serialize(payload.getResult()));
    if (updated == 1) {
      jobsCompleted.increment();
      log.info(
          "Marked job [{}] COMPLETED from event [{}]", payload.getJobId(), envelope.getEventId());
      return;
    }
    ignoreOrRecordConflict(job);
  }

  private void failJob(EventEnvelope<?> envelope, JobFailedPayload payload) {
    Job job = requireJob(payload.getJobId());
    if (!recordProcessedEvent(envelope, payload.getJobId())) {
      return;
    }
    String failureMessage = payload.getErrorCode() + ": " + payload.getMessage();
    int updated = jobRepository.markFailed(payload.getJobId(), failureMessage);
    if (updated == 1) {
      jobsFailed.increment();
      log.info("Marked job [{}] FAILED from event [{}]", payload.getJobId(), envelope.getEventId());
      return;
    }
    ignoreOrRecordConflict(job);
  }

  private Job requireJob(UUID jobId) {
    return jobRepository
        .findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown job: " + jobId));
  }

  private void ignoreOrRecordConflict(Job job) {
    if (isTerminal(job)) {
      ignoredTerminalEvents.increment();
      log.info("Ignoring worker event for terminal job [{}]", job.getId());
      return;
    }
    stateTransitionConflicts.increment();
    log.info(
        "Ignoring worker event that cannot transition job [{}] from [{}]",
        job.getId(),
        job.getStatus());
  }

  private boolean recordProcessedEvent(EventEnvelope<?> envelope, UUID jobId) {
    try {
      processedEventRepository.save(
          new ProcessedEvent(envelope.getEventId(), envelope.getEventType(), jobId.toString()));
      return true;
    } catch (DataIntegrityViolationException e) {
      duplicateResultEvents.increment();
      log.info("Event [{}] was already processed concurrently", envelope.getEventId());
      return false;
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
      String traceId =
          UUID.randomUUID().toString().replace("-", "")
              + UUID.randomUUID().toString().replace("-", "");
      return "00-" + traceId.substring(0, 32) + "-" + traceId.substring(32, 48) + "-01";
    }
    return "00-"
        + tracer.currentSpan().context().traceId()
        + "-"
        + tracer.currentSpan().context().spanId()
        + "-01";
  }

  public record SubmitJobResult(JobResponse response, boolean created) {
    static SubmitJobResult created(JobResponse response) {
      return new SubmitJobResult(response, true);
    }

    static SubmitJobResult duplicate(JobResponse response) {
      return new SubmitJobResult(response, false);
    }
  }
}
