package com.greennode.api_gateway.repository;

import com.greennode.api_gateway.entity.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for managing outbox events. The key query uses FOR UPDATE SKIP LOCKED to enable
 * concurrent polling by multiple API gateway instances without blocking or duplicate processing.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetch pending/failed events for processing with pessimistic locking. SKIP LOCKED prevents
     * multiple API gateway instances from processing the same event. Orders by created_at for FIFO
     * processing.
     */
    @Query(
            value =
                    """
                    SELECT * FROM outbox_events
                    WHERE status IN ('PENDING', 'FAILED')
                    AND retry_count < max_retries
                    ORDER BY created_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<OutboxEvent> findPendingEventsForProcessing(@Param("batchSize") int batchSize);
}
