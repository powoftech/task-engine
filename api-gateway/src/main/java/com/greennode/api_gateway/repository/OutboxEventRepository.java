package com.greennode.api_gateway.repository;

import com.greennode.api_gateway.entity.OutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for managing outbox events. Events are captured by Debezium CDC and automatically
 * published to RabbitMQ via change data capture from the PostgreSQL WAL.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    // No custom queries needed - Debezium handles event publishing via CDC
}
