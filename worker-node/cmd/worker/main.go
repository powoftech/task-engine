package main

import (
	"log"
	"os"
	"os/signal"
	"syscall"

	"greennode/worker-node/internal/db"
	"greennode/worker-node/internal/processor"
)

func main() {
	log.Println("Starting Go Worker Node...")

	// 1. Connect to Database
	database, err := db.Connect()
	if err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}
	defer database.Conn.Close()

	// 2. Connect to RabbitMQ
	proc, err := processor.New(database)
	if err != nil {
		log.Fatalf("Failed to initialize message processor: %v", err)
	}
	defer proc.Close()

	// 3. Handle Graceful Shutdown (Architectural Constraint)
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	// Run consumer in a separate goroutine
	go func() {
		if err := proc.Start(); err != nil {
			log.Fatalf("Consumer error: %v", err)
		}
	}()

	// 4. Block until we receive a termination signal
	sig := <-sigChan
	log.Printf("Received signal %v. Initiating graceful shutdown...", sig)

	// Connections are closed automatically by defers as main exits.
	log.Println("Worker shut down successfully.")
}
