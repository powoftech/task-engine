package cache

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/redis/go-redis/v9"
)

type Cache struct {
	Client *redis.Client
}

// Connect initializes the connection to Redis
func Connect() (*Cache, error) {
	host := getEnv("REDIS_HOST", "localhost")
	port := getEnv("REDIS_PORT", "6379")

	client := redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%s", host, port),
		Password: "", // No password for local dev
		DB:       0,  // Use default DB
	})

	// Ping to verify connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	log.Println("Connected to Redis successfully")
	return &Cache{Client: client}, nil
}

// ClaimJob implements a distributed lock using Redis SETNX.
// It returns true if the lock was successfully acquired, false if it already exists.
func (c *Cache) ClaimJob(ctx context.Context, jobID string) (bool, error) {
	key := fmt.Sprintf("job:claim:%s", jobID)

	// We set a TTL of 1 hour to ensure locks expire if a worker crashes
	// catastrophically during processing, preventing permanent deadlocks.
	expiration := 1 * time.Hour

	// SetNX is atomic. It returns a boolean indicating if the key was set.
	acquired, err := c.Client.SetNX(ctx, key, "locked", expiration).Result()
	if err != nil {
		return false, fmt.Errorf("redis error during SetNX for job %s: %w", jobID, err)
	}

	return acquired, nil
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}
