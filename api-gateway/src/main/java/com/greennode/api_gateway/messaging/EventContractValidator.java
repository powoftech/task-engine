package com.greennode.api_gateway.messaging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventContractValidator {

  private static final Map<String, String> SCHEMAS =
      Map.of(
          EventTypes.JOB_REQUESTED, "job-requested.v1.schema.json",
          EventTypes.JOB_PROCESSING, "job-processing.v1.schema.json",
          EventTypes.JOB_COMPLETED, "job-completed.v1.schema.json",
          EventTypes.JOB_FAILED, "job-failed.v1.schema.json");

  private final JsonMapper jsonMapper;

  public EventContractValidator(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public void validate(String rawEventJson) {
    Map<String, Object> event = readObject(rawEventJson, "event");
    Object eventType = event.get("eventType");
    if (!(eventType instanceof String type) || !SCHEMAS.containsKey(type)) {
      throw new IllegalArgumentException("Unsupported event type: " + eventType);
    }
    validateObject(event, schemaFor(type), "$");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> schemaFor(String eventType) {
    String schemaFile = SCHEMAS.get(eventType);
    for (Path base :
        List.of(Path.of("contracts", "events"), Path.of("..", "contracts", "events"))) {
      Path candidate = base.resolve(schemaFile);
      if (Files.exists(candidate)) {
        try {
          return jsonMapper.readValue(Files.readString(candidate), Map.class);
        } catch (Exception e) {
          throw new IllegalStateException("Failed to read event schema " + candidate, e);
        }
      }
    }
    throw new IllegalStateException("Event schema not found: " + schemaFile);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readObject(String rawJson, String label) {
    try {
      Object value = jsonMapper.readValue(rawJson, Map.class);
      if (value instanceof Map<?, ?> map) {
        return (Map<String, Object>) map;
      }
      throw new IllegalArgumentException(label + " must be a JSON object");
    } catch (JacksonException e) {
      throw new IllegalArgumentException(label + " is not valid JSON", e);
    }
  }

  @SuppressWarnings("unchecked")
  private void validateObject(Map<String, Object> value, Map<String, Object> schema, String path) {
    List<String> required = (List<String>) schema.getOrDefault("required", List.of());
    for (String field : required) {
      if (!value.containsKey(field) || value.get(field) == null) {
        throw new IllegalArgumentException(path + "." + field + " is required");
      }
    }

    Map<String, Object> properties =
        (Map<String, Object>) schema.getOrDefault("properties", Map.of());
    if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
      Set<String> allowed = properties.keySet();
      for (String key : value.keySet()) {
        if (!allowed.contains(key)) {
          throw new IllegalArgumentException(path + "." + key + " is not allowed");
        }
      }
    }

    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      String field = entry.getKey();
      if (!value.containsKey(field) || value.get(field) == null) {
        continue;
      }
      validateValue(value.get(field), (Map<String, Object>) entry.getValue(), path + "." + field);
    }
  }

  @SuppressWarnings("unchecked")
  private void validateValue(Object value, Map<String, Object> schema, String path) {
    if (schema.containsKey("const") && !schema.get("const").equals(value)) {
      throw new IllegalArgumentException(path + " must equal " + schema.get("const"));
    }

    Object type = schema.get("type");
    if ("object".equals(type)) {
      if (!(value instanceof Map<?, ?> objectValue)) {
        throw new IllegalArgumentException(path + " must be an object");
      }
      validateObject((Map<String, Object>) objectValue, schema, path);
      return;
    }
    if ("string".equals(type)) {
      if (!(value instanceof String stringValue)) {
        throw new IllegalArgumentException(path + " must be a string");
      }
      validateString(stringValue, schema, path);
      return;
    }
    if ("integer".equals(type)) {
      if (!(value instanceof Integer)) {
        throw new IllegalArgumentException(path + " must be an integer");
      }
      validateInteger((Integer) value, schema, path);
    }
  }

  private void validateString(String value, Map<String, Object> schema, String path) {
    Object minLength = schema.get("minLength");
    if (minLength instanceof Integer minimum && value.length() < minimum) {
      throw new IllegalArgumentException(path + " is too short");
    }
    Object format = schema.get("format");
    if ("uuid".equals(format)) {
      try {
        UUID.fromString(value);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(path + " must be a UUID", e);
      }
    }
    if ("date-time".equals(format)) {
      try {
        Instant.parse(value);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException(path + " must be an ISO-8601 instant", e);
      }
    }
  }

  private void validateInteger(Integer value, Map<String, Object> schema, String path) {
    Object minimum = schema.get("minimum");
    if (minimum instanceof Integer min && value < min) {
      throw new IllegalArgumentException(path + " is below minimum " + min);
    }
    Object maximum = schema.get("maximum");
    if (maximum instanceof Integer max && value > max) {
      throw new IllegalArgumentException(path + " is above maximum " + max);
    }
  }
}
