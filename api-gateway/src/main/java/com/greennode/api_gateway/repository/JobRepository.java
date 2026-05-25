package com.greennode.api_gateway.repository;

import com.greennode.api_gateway.entity.Job;
import com.greennode.api_gateway.entity.JobStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {
  Optional<Job> findByClientRequestId(String clientRequestId);

  Page<Job> findByStatus(JobStatus status, Pageable pageable);

  Page<Job> findByClientRequestId(String clientRequestId, Pageable pageable);

  Page<Job> findByStatusAndClientRequestId(
      JobStatus status, String clientRequestId, Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value = "update jobs set status = 'PROCESSING' where id = :id and status = 'QUEUED'",
      nativeQuery = true)
  int markProcessing(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "update jobs set status = 'COMPLETED', result = cast(:result as jsonb), "
              + "failure_message = null where id = :id and status in ('QUEUED', 'PROCESSING')",
      nativeQuery = true)
  int markCompleted(@Param("id") UUID id, @Param("result") String result);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "update jobs set status = 'FAILED', failure_message = :failureMessage "
              + "where id = :id and status in ('QUEUED', 'PROCESSING')",
      nativeQuery = true)
  int markFailed(@Param("id") UUID id, @Param("failureMessage") String failureMessage);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "update jobs set status = 'CANCELLED' where id = :id and status in ('QUEUED',"
              + " 'PROCESSING')",
      nativeQuery = true)
  int markCancelled(@Param("id") UUID id);
}
