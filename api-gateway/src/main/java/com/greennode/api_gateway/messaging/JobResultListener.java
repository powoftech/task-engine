package com.greennode.api_gateway.messaging;

import com.greennode.api_gateway.service.JobService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class JobResultListener {

  private static final Logger log = LoggerFactory.getLogger(JobResultListener.class);
  private static final int MAX_ATTEMPTS = 3;

  private final JobService jobService;
  private final RabbitTemplate rabbitTemplate;
  private final Counter resultEventsConsumed;
  private final Counter resultEventsRejected;
  private final Counter resultEventsRetried;
  private final Counter resultEventsDeadLettered;

  public JobResultListener(
      JobService jobService, RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
    this.jobService = jobService;
    this.rabbitTemplate = rabbitTemplate;
    this.resultEventsConsumed = meterRegistry.counter("task_engine.events.results.consumed");
    this.resultEventsRejected = meterRegistry.counter("task_engine.events.results.rejected");
    this.resultEventsRetried = meterRegistry.counter("task_engine.events.results.retried");
    this.resultEventsDeadLettered =
        meterRegistry.counter("task_engine.events.results.dead_lettered");
  }

  @RabbitListener(queues = RabbitTopology.RESULT_QUEUE)
  public void onMessage(Message message, Channel channel) throws Exception {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    String body = new String(message.getBody());
    try {
      jobService.applyWorkerEvent(body);
      resultEventsConsumed.increment();
      channel.basicAck(deliveryTag, false);
    } catch (IllegalArgumentException e) {
      resultEventsRejected.increment();
      log.warn("Dead-lettering invalid result event: {}", e.getMessage());
      publishDeadLetter(message);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException e) {
      int nextAttempt = currentAttempt(message) + 1;
      resultEventsRejected.increment();
      if (nextAttempt > MAX_ATTEMPTS) {
        log.error("Dead-lettering result event after {} attempts", MAX_ATTEMPTS, e);
        publishDeadLetter(message);
        resultEventsDeadLettered.increment();
      } else {
        log.warn("Retrying result event attempt {}", nextAttempt, e);
        publishRetry(message, nextAttempt);
        resultEventsRetried.increment();
      }
      channel.basicAck(deliveryTag, false);
    }
  }

  private void publishRetry(Message source, int attempt) {
    rabbitTemplate.convertAndSend(
        RabbitTopology.RESULT_RETRY_EXCHANGE,
        RabbitTopology.RESULT_RETRY_ROUTING_KEY_PREFIX + attempt,
        source.getBody(),
        retry -> {
          copyHeaders(source, retry);
          retry.getMessageProperties().setHeader(RabbitTopology.ATTEMPT_HEADER, attempt);
          return retry;
        });
  }

  private void publishDeadLetter(Message source) {
    rabbitTemplate.convertAndSend(
        RabbitTopology.RESULT_DLQ_EXCHANGE,
        RabbitTopology.RESULT_DLQ_ROUTING_KEY,
        source.getBody(),
        deadLetter -> {
          copyHeaders(source, deadLetter);
          return deadLetter;
        });
  }

  private void copyHeaders(Message source, Message target) {
    for (Map.Entry<String, Object> header : source.getMessageProperties().getHeaders().entrySet()) {
      target.getMessageProperties().setHeader(header.getKey(), header.getValue());
    }
    target.getMessageProperties().setContentType(source.getMessageProperties().getContentType());
    target.getMessageProperties().setMessageId(source.getMessageProperties().getMessageId());
    target
        .getMessageProperties()
        .setCorrelationId(source.getMessageProperties().getCorrelationId());
  }

  private int currentAttempt(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(RabbitTopology.ATTEMPT_HEADER);
    if (value instanceof Integer attempt) {
      return attempt;
    }
    if (value instanceof Long attempt) {
      return attempt.intValue();
    }
    if (value instanceof String attempt) {
      try {
        return Integer.parseInt(attempt);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }
}
