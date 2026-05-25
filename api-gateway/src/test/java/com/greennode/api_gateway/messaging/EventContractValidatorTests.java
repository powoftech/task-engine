package com.greennode.api_gateway.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventContractValidatorTests {

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final EventContractValidator validator = new EventContractValidator(jsonMapper);

  @Test
  void validatesAllKnownEventContracts() throws Exception {
    validator.validate(
        eventJson(
            EventTypes.JOB_REQUESTED,
            Map.of("jobId", UUID.randomUUID().toString(), "taskType", "matrix", "complexity", 1)));
    validator.validate(
        eventJson(
            EventTypes.JOB_PROCESSING,
            Map.of("jobId", UUID.randomUUID().toString(), "startedAt", Instant.now().toString())));
    validator.validate(
        eventJson(
            EventTypes.JOB_COMPLETED,
            Map.of(
                "jobId",
                UUID.randomUUID().toString(),
                "processedInSeconds",
                1,
                "result",
                Map.of("status", "success"))));
    validator.validate(
        eventJson(
            EventTypes.JOB_FAILED,
            Map.of(
                "jobId",
                UUID.randomUUID().toString(),
                "errorCode",
                "WORKER_FAILURE",
                "message",
                "failed")));
  }

  @Test
  void rejectsAdditionalEnvelopeProperties() throws Exception {
    String event =
        jsonMapper.writeValueAsString(
            Map.of(
                "eventId",
                UUID.randomUUID().toString(),
                "eventType",
                EventTypes.JOB_FAILED,
                "schemaVersion",
                1,
                "occurredAt",
                Instant.now().toString(),
                "traceparent",
                "00-00000000000000000000000000000000-0000000000000000-00",
                "correlationId",
                UUID.randomUUID().toString(),
                "causationId",
                UUID.randomUUID().toString(),
                "unexpected",
                true,
                "payload",
                Map.of(
                    "jobId",
                    UUID.randomUUID().toString(),
                    "errorCode",
                    "WORKER_FAILURE",
                    "message",
                    "failed")));

    assertThatThrownBy(() -> validator.validate(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unexpected");
  }

  private String eventJson(String eventType, Map<String, Object> payload) throws Exception {
    return jsonMapper.writeValueAsString(
        Map.of(
            "eventId",
            UUID.randomUUID().toString(),
            "eventType",
            eventType,
            "schemaVersion",
            1,
            "occurredAt",
            Instant.now().toString(),
            "traceparent",
            "00-00000000000000000000000000000000-0000000000000000-00",
            "correlationId",
            UUID.randomUUID().toString(),
            "causationId",
            UUID.randomUUID().toString(),
            "payload",
            payload));
  }
}
