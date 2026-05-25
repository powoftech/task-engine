package com.greennode.api_gateway.service;

import com.greennode.api_gateway.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
    prefix = "task-engine.outbox.cleanup",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxCleanupService {

  private static final Logger log = LoggerFactory.getLogger(OutboxCleanupService.class);

  private final OutboxEventRepository outboxEventRepository;
  private final Duration retention;
  private final Counter deletedEvents;

  public OutboxCleanupService(
      OutboxEventRepository outboxEventRepository,
      @Value("${task-engine.outbox.cleanup.retention:PT24H}") Duration retention,
      MeterRegistry meterRegistry) {
    this.outboxEventRepository = outboxEventRepository;
    this.retention = retention;
    this.deletedEvents = meterRegistry.counter("task_engine.outbox.deleted");
  }

  @Transactional
  @Scheduled(fixedDelayString = "${task-engine.outbox.cleanup.fixed-delay:PT1H}")
  public void deleteOldOutboxEvents() {
    Instant cutoff = Instant.now().minus(retention);
    int deleted = outboxEventRepository.deleteCreatedBefore(cutoff);
    if (deleted > 0) {
      deletedEvents.increment(deleted);
      log.info("Deleted {} outbox events older than {}", deleted, cutoff);
    }
  }
}
