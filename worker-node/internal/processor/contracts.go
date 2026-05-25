package processor

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
)

var schemaFiles = map[string]string{
	jobRequested:  "job-requested.v1.schema.json",
	jobProcessing: "job-processing.v1.schema.json",
	jobCompleted:  "job-completed.v1.schema.json",
	jobFailed:     "job-failed.v1.schema.json",
}

func validateEventContract(body []byte) error {
	var event map[string]any
	if err := json.Unmarshal(body, &event); err != nil {
		return fmt.Errorf("invalid event JSON: %w", err)
	}
	eventType, ok := event["eventType"].(string)
	if !ok {
		return errors.New("eventType is required")
	}
	schemaFile, ok := schemaFiles[eventType]
	if !ok {
		return fmt.Errorf("unsupported event type %q", eventType)
	}
	schema, err := loadSchema(schemaFile)
	if err != nil {
		return err
	}
	return validateObject(event, schema, "$")
}

func loadSchema(file string) (map[string]any, error) {
	for _, base := range schemaSearchPaths() {
		body, err := os.ReadFile(filepath.Join(base, file))
		if err != nil {
			continue
		}
		var schema map[string]any
		if err := json.Unmarshal(body, &schema); err != nil {
			return nil, fmt.Errorf("failed to parse schema %s: %w", file, err)
		}
		return schema, nil
	}
	return nil, fmt.Errorf("event schema not found: %s", file)
}

func schemaSearchPaths() []string {
	paths := []string{}
	current := "."
	for i := 0; i < 6; i++ {
		paths = append(paths, filepath.Join(current, "contracts", "events"))
		current = filepath.Join(current, "..")
	}
	return paths
}

func validateObject(value map[string]any, schema map[string]any, path string) error {
	if required, ok := schema["required"].([]any); ok {
		for _, item := range required {
			field, ok := item.(string)
			if !ok {
				continue
			}
			if _, exists := value[field]; !exists || value[field] == nil {
				return fmt.Errorf("%s.%s is required", path, field)
			}
		}
	}

	properties := map[string]any{}
	if rawProperties, ok := schema["properties"].(map[string]any); ok {
		properties = rawProperties
	}
	if additional, ok := schema["additionalProperties"].(bool); ok && !additional {
		for key := range value {
			if _, allowed := properties[key]; !allowed {
				return fmt.Errorf("%s.%s is not allowed", path, key)
			}
		}
	}

	for key, rawProperty := range properties {
		fieldValue, exists := value[key]
		if !exists || fieldValue == nil {
			continue
		}
		propertySchema, ok := rawProperty.(map[string]any)
		if !ok {
			continue
		}
		if err := validateValue(fieldValue, propertySchema, path+"."+key); err != nil {
			return err
		}
	}
	return nil
}

func validateValue(value any, schema map[string]any, path string) error {
	if expected, ok := schema["const"]; ok && expected != value {
		return fmt.Errorf("%s must equal %v", path, expected)
	}
	switch schema["type"] {
	case "object":
		objectValue, ok := value.(map[string]any)
		if !ok {
			return fmt.Errorf("%s must be an object", path)
		}
		return validateObject(objectValue, schema, path)
	case "string":
		stringValue, ok := value.(string)
		if !ok {
			return fmt.Errorf("%s must be a string", path)
		}
		return validateString(stringValue, schema, path)
	case "integer":
		numberValue, ok := value.(float64)
		if !ok || numberValue != float64(int(numberValue)) {
			return fmt.Errorf("%s must be an integer", path)
		}
		return validateInteger(int(numberValue), schema, path)
	default:
		return nil
	}
}

func validateString(value string, schema map[string]any, path string) error {
	if minLength, ok := schema["minLength"].(float64); ok && len(value) < int(minLength) {
		return fmt.Errorf("%s is too short", path)
	}
	format, _ := schema["format"].(string)
	switch strings.ToLower(format) {
	case "uuid":
		if _, err := uuid.Parse(value); err != nil {
			return fmt.Errorf("%s must be a UUID: %w", path, err)
		}
	case "date-time":
		if _, err := time.Parse(time.RFC3339Nano, value); err != nil {
			return fmt.Errorf("%s must be an ISO-8601 instant: %w", path, err)
		}
	}
	return nil
}

func validateInteger(value int, schema map[string]any, path string) error {
	if minimum, ok := schema["minimum"].(float64); ok && value < int(minimum) {
		return fmt.Errorf("%s is below minimum %d", path, int(minimum))
	}
	if maximum, ok := schema["maximum"].(float64); ok && value > int(maximum) {
		return fmt.Errorf("%s is above maximum %d", path, int(maximum))
	}
	return nil
}
