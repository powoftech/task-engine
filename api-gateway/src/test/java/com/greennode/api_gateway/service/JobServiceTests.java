package com.greennode.api_gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greennode.api_gateway.dto.JobRequest;
import com.greennode.api_gateway.dto.JobResponse;
import com.greennode.api_gateway.entity.Job;
import com.greennode.api_gateway.entity.JobStatus;
import com.greennode.api_gateway.entity.OutboxEvent;
import com.greennode.api_gateway.messaging.EventContractValidator;
import com.greennode.api_gateway.messaging.EventEnvelope;
import com.greennode.api_gateway.messaging.EventTypes;
import com.greennode.api_gateway.messaging.JobCompletedPayload;
import com.greennode.api_gateway.repository.JobRepository;
import com.greennode.api_gateway.repository.OutboxEventRepository;
import com.greennode.api_gateway.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

class JobServiceTests {

  private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
  private final OutboxEventRepository outboxEventRepository =
      Mockito.mock(OutboxEventRepository.class);
  private final ProcessedEventRepository processedEventRepository =
      Mockito.mock(ProcessedEventRepository.class);
  private final Tracer tracer = Mockito.mock(Tracer.class);
  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final EventContractValidator contractValidator = new EventContractValidator(jsonMapper);
  private final JobService service =
      new JobService(
          jobRepository,
          outboxEventRepository,
          processedEventRepository,
          contractValidator,
          jsonMapper,
          tracer,
          new SimpleMeterRegistry());

  @Test
  void submitJobCreatesQueuedJobAndOutboxEvent() {
    JobRequest request = new JobRequest();
    request.setTaskType("matrix_multiplication");
    request.setComplexity(2);
    request.setClientRequestId("client-1");

    when(jobRepository.findByClientRequestId("client-1")).thenReturn(Optional.empty());
    when(jobRepository.saveAndFlush(any(Job.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(outboxEventRepository.save(any(OutboxEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    JobResponse response = service.submitJob(request).response();

    assertThat(response.getStatus()).isEqualTo(JobStatus.QUEUED);
    assertThat(response.getTaskType()).isEqualTo("matrix_multiplication");

    ArgumentCaptor<OutboxEvent> outboxEvent = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxEventRepository).save(outboxEvent.capture());
    assertThat(outboxEvent.getValue().getType()).isEqualTo(EventTypes.JOB_REQUESTED);
    assertThat(outboxEvent.getValue().getPayload()).contains("\"eventType\":\"job.requested.v1\"");
  }

  @Test
  void applyResultEventIgnoresDuplicateEvent() throws Exception {
    UUID eventId = UUID.randomUUID();
    String eventJson = completedEventJson(eventId, UUID.randomUUID());
    when(processedEventRepository.existsById(eventId)).thenReturn(true);

    assertThat(service.applyWorkerEvent(eventJson)).isTrue();

    verify(jobRepository, never()).findById(any());
  }

  @Test
  void applyResultEventCompletesOwnedJob() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    Job job = new Job(jobId, "matrix_multiplication", 1, JobStatus.QUEUED, null, UUID.randomUUID());
    when(processedEventRepository.existsById(eventId)).thenReturn(false);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    assertThat(service.applyWorkerEvent(completedEventJson(eventId, jobId))).isTrue();

    verify(jobRepository).markCompleted(jobId, "{\"status\":\"success\"}");
  }

  private String completedEventJson(UUID eventId, UUID jobId) throws Exception {
    JobCompletedPayload payload = new JobCompletedPayload();
    payload.setJobId(jobId);
    payload.setProcessedInSeconds(1);
    payload.setResult(Map.of("status", "success"));
    EventEnvelope<JobCompletedPayload> envelope =
        new EventEnvelope<>(
            eventId,
            EventTypes.JOB_COMPLETED,
            1,
            Instant.now().toString(),
            "00-00000000000000000000000000000000-0000000000000000-00",
            UUID.randomUUID(),
            UUID.randomUUID(),
            payload);
    return jsonMapper.writeValueAsString(envelope);
  }
}
