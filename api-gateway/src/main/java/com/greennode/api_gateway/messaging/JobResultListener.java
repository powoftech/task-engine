package com.greennode.api_gateway.messaging;

import com.greennode.api_gateway.service.JobService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class JobResultListener {

  private static final Logger log = LoggerFactory.getLogger(JobResultListener.class);

  private final JobService jobService;
  private final Counter resultEventsConsumed;
  private final Counter resultEventsRejected;

  public JobResultListener(JobService jobService, MeterRegistry meterRegistry) {
    this.jobService = jobService;
    this.resultEventsConsumed = meterRegistry.counter("task_engine.events.results.consumed");
    this.resultEventsRejected = meterRegistry.counter("task_engine.events.results.rejected");
  }

  @RabbitListener(queues = RabbitTopology.RESULT_QUEUE)
  public void onMessage(Message message, Channel channel) throws Exception {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    String body = new String(message.getBody());
    try {
      jobService.applyResultEvent(body);
      resultEventsConsumed.increment();
      channel.basicAck(deliveryTag, false);
    } catch (IllegalArgumentException e) {
      resultEventsRejected.increment();
      log.warn("Rejecting invalid result event: {}", e.getMessage());
      channel.basicNack(deliveryTag, false, false);
    } catch (RuntimeException e) {
      resultEventsRejected.increment();
      log.error("Result event processing failed", e);
      channel.basicNack(deliveryTag, false, true);
    }
  }
}
