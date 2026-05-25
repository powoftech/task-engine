package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

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

	proc, err := processor.New()
	if err != nil {
		log.Fatalf("Failed to initialize message processor: %v", err)
	}
	defer proc.Close()

	server := &http.Server{Addr: ":" + getEnv("HTTP_PORT", "8090")}
	http.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		if proc.Ready() {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("ok"))
			return
		}
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte("not ready"))
	})
	http.HandleFunc("/metrics", processor.MetricsHandler)

	go func() {
		log.Printf("Worker health endpoint listening on %s", server.Addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Health server error: %v", err)
		}
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		if err := proc.Start(ctx); err != nil {
			log.Fatalf("Consumer error: %v", err)
		}
	}()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigChan
	log.Printf("Received signal %v. Initiating graceful shutdown...", sig)
	cancel()

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("Health server shutdown failed: %v", err)
	}

	log.Println("Worker shut down successfully.")
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}
