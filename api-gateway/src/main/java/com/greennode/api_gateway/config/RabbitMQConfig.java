package com.greennode.api_gateway.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the RabbitMQ topology. This ensures the Exchange and Queue exist when the application
 * boots up.
 */
@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_NAME = "ai_tasks_queue";
    public static final String EXCHANGE_NAME = "ai_tasks_exchange";
    public static final String ROUTING_KEY = "ai_tasks_routing_key";

    /** Define the queue. 'true' means it is durable (survives broker restarts). */
    @Bean
    public Queue taskQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    /** Define a Direct Exchange to route messages straight to our queue. */
    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    /** Bind the Queue to the Exchange with the specific Routing Key. */
    @Bean
    public Binding binding(Queue taskQueue, DirectExchange taskExchange) {
        return BindingBuilder.bind(taskQueue).to(taskExchange).with(ROUTING_KEY);
    }

    /**
     * Use Jackson to serialize Java objects to JSON payloads for RabbitMQ. This is critical because
     * our consumer is written in Go.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
