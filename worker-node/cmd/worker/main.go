package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"greennode/worker-node/internal/cache"
	"greennode/worker-node/internal/db"
	"greennode/worker-node/internal/processor"
	"greennode/worker-node/internal/telemetry"
)

func main() {
	log.Println("Starting Go Worker Node...")

	shutdown, err := telemetry.InitProvider()
	if err != nil {
		log.Fatalf("Failed to initialize OpenTelemetry: %v", err)
	}
	defer func() {
		if err := shutdown(context.Background()); err != nil {
			log.Printf("Failed to shutdown OpenTelemetry provider: %v", err)
		}
	}()

	database, err := db.Connect()
	if err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}
	defer database.Conn.Close()

	cache, err := cache.Connect()
	if err != nil {
		log.Fatalf("Failed to initialize cache: %v", err)
	}
	defer cache.Client.Close()

	proc, err := processor.New(database, cache)
	if err != nil {
		log.Fatalf("Failed to initialize message processor: %v", err)
	}
	defer proc.Close()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		if err := proc.Start(); err != nil {
			log.Fatalf("Consumer error: %v", err)
		}
	}()

	sig := <-sigChan
	log.Printf("Received signal %v. Initiating graceful shutdown...", sig)

	log.Println("Worker shut down successfully.")
}
