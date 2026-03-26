FROM jaegertracing/jaeger:latest

# Add `curl` for health checks
# Note: The base Jaeger image is based on Alpine Linux, which uses `apk` for package management.
# Use root user to install curl, then switch back to the default user for running Jaeger.
USER root
RUN apk add --no-cache curl