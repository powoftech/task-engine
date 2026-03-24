# Build stage
FROM golang:1.26-alpine AS builder

WORKDIR /app

# Copy go mod files
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build the worker binary
RUN CGO_ENABLED=0 GOOS=linux go build -o worker ./cmd/worker

# Runtime stage
FROM alpine:latest

WORKDIR /app

# Copy the binary from builder
COPY --from=builder /app/worker .

# Run as non-root user for security
RUN addgroup -S workergroup && adduser -S workeruser -G workergroup
USER workeruser

# Start the worker
CMD ["./worker"]
