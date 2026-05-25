FROM golang:1.26-alpine AS builder

WORKDIR /app

COPY worker-node/go.mod worker-node/go.sum ./
RUN go mod download

COPY worker-node/cmd ./cmd
COPY worker-node/internal ./internal
COPY contracts ./contracts

RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o worker ./cmd/worker

FROM alpine:3.22

WORKDIR /app

RUN apk --no-cache add ca-certificates curl

COPY --from=builder /app/worker .
COPY --from=builder /app/contracts ./contracts

RUN addgroup -S workergroup && adduser -S workeruser -G workergroup
USER workeruser

CMD ["./worker"]
