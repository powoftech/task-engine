package com.greennode.api_gateway.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {
  public static final String RESULT_EXCHANGE = "worker.results";
  public static final String RESULT_QUEUE = "api.job-results.queue";
  public static final String RESULT_DLQ = "api.job-results.dlq";
  public static final String RESULT_DLQ_EXCHANGE = "worker.results.dlx";

  @Bean
  public DirectExchange resultExchange() {
    return ExchangeBuilder.directExchange(RESULT_EXCHANGE).durable(true).build();
  }

  @Bean
  public DirectExchange resultDeadLetterExchange() {
    return ExchangeBuilder.directExchange(RESULT_DLQ_EXCHANGE).durable(true).build();
  }

  @Bean
  public Queue resultQueue() {
    return QueueBuilder.durable(RESULT_QUEUE)
        .deadLetterExchange(RESULT_DLQ_EXCHANGE)
        .deadLetterRoutingKey("api.job-results.failed")
        .build();
  }

  @Bean
  public Queue resultDeadLetterQueue() {
    return QueueBuilder.durable(RESULT_DLQ).build();
  }

  @Bean
  public Binding completedResultBinding(Queue resultQueue, DirectExchange resultExchange) {
    return BindingBuilder.bind(resultQueue).to(resultExchange).with(EventTypes.JOB_COMPLETED);
  }

  @Bean
  public Binding failedResultBinding(Queue resultQueue, DirectExchange resultExchange) {
    return BindingBuilder.bind(resultQueue).to(resultExchange).with(EventTypes.JOB_FAILED);
  }

  @Bean
  public Binding resultDeadLetterBinding(
      Queue resultDeadLetterQueue, DirectExchange resultDeadLetterExchange) {
    return BindingBuilder.bind(resultDeadLetterQueue)
        .to(resultDeadLetterExchange)
        .with("api.job-results.failed");
  }
}
