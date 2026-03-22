package com.greennode.api_gateway.service;

import com.greennode.api_gateway.config.RabbitMQConfig;
import com.greennode.api_gateway.dto.JobMessage;
import com.greennode.api_gateway.dto.JobRequest;
import com.greennode.api_gateway.entity.Job;
import com.greennode.api_gateway.entity.JobStatus;
import com.greennode.api_gateway.repository.JobRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the core business logic. Enforces the architectural rule: Log all incoming requests
 * and outgoing RabbitMQ publishes.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final RabbitTemplate rabbitTemplate;

    // Constructor Injection (Preferred over @Autowired)
    public JobService(JobRepository jobRepository, RabbitTemplate rabbitTemplate) {
        this.jobRepository = jobRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Submits a new job. @Transactional ensures that if the DB save fails, we don't accidentally
     * publish a ghost message to MQ.
     */
    @Transactional
    public Job submitJob(JobRequest request) {
        UUID jobId = UUID.randomUUID();

        // 1. Initialize and save to DB
        Job job = new Job(jobId, request.getTaskType(), JobStatus.PENDING);
        job = jobRepository.save(job);
        log.info("Persisted new job [{}] to database with status PENDING", jobId);

        // 2. Publish to RabbitMQ
        JobMessage message = new JobMessage(jobId, request.getTaskType(), request.getComplexity());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY, message);
        log.info(
                "Published job [{}] to RabbitMQ Exchange [{}]",
                jobId,
                RabbitMQConfig.EXCHANGE_NAME);

        return job;
    }

    public Optional<Job> getJobStatus(UUID jobId) {
        return jobRepository.findById(jobId);
    }
}
