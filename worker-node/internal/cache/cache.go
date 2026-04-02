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

func Connect() (*Cache, error) {
	host := getEnv("REDIS_HOST", "localhost")
	port := getEnv("REDIS_PORT", "6379")

	client := redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%s", host, port),
		Password: "", // No password for local dev
		DB:       0,  // Use default DB
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	log.Println("Connected to Redis successfully")
	return &Cache{Client: client}, nil
}

func (c *Cache) ClaimJob(ctx context.Context, jobID string) (bool, error) {
	key := fmt.Sprintf("job:claim:%s", jobID)

	expiration := 1 * time.Hour

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
