package com.greennode.api_gateway.service;

import com.greennode.api_gateway.config.RabbitMQConfig;
import com.greennode.api_gateway.dto.JobMessage;
import com.greennode.api_gateway.entity.OutboxEvent;
import com.greennode.api_gateway.entity.OutboxEventStatus;
import com.greennode.api_gateway.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scheduled service that polls the outbox table and publishes pending events to RabbitMQ. This
 * service implements the publisher side of the Transactional Outbox Pattern.
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);
    private static final int BATCH_SIZE = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;

    public OutboxPublisherService(
            OutboxEventRepository outboxEventRepository,
            RabbitTemplate rabbitTemplate,
            JsonMapper jsonMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Scheduled poller that runs every 1 second (1000ms fixed delay). Processes pending outbox
     * events and publishes them to RabbitMQ.
     */
    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findPendingEventsForProcessing(BATCH_SIZE);

        if (events.isEmpty()) {
            return; // No work to do
        }

        log.info("Processing {} pending outbox events", events.size());

        for (OutboxEvent event : events) {
            processEvent(event);
        }
    }

    @Transactional
    protected void processEvent(OutboxEvent event) {
        try {
            // Mark as PROCESSING to prevent duplicate work
            event.setStatus(OutboxEventStatus.PROCESSING);
            outboxEventRepository.save(event);

            // Deserialize and publish to RabbitMQ
            JobMessage message = jsonMapper.readValue(event.getPayload(), JobMessage.class);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY, message);

            // Mark as successfully published
            event.setStatus(OutboxEventStatus.PUBLISHED);
            event.setProcessedAt(Instant.now());
            outboxEventRepository.save(event);

            log.info(
                    "Published outbox event [{}] for job [{}] to RabbitMQ",
                    event.getId(),
                    event.getAggregateId());

        } catch (Exception e) {
            handlePublishError(event, e);
        }
    }

    @Transactional
    protected void handlePublishError(OutboxEvent event, Exception error) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setErrorMessage(error.getMessage());

        if (event.getRetryCount() >= event.getMaxRetries()) {
            event.setStatus(OutboxEventStatus.FAILED);
            log.error(
                    "Outbox event [{}] permanently failed after {} retries: {}",
                    event.getId(),
                    event.getRetryCount(),
                    error.getMessage());
        } else {
            event.setStatus(OutboxEventStatus.PENDING); // Retry
            log.warn(
                    "Outbox event [{}] failed (attempt {}/{}): {}",
                    event.getId(),
                    event.getRetryCount(),
                    event.getMaxRetries(),
                    error.getMessage());
        }

        outboxEventRepository.save(event);
    }
}
