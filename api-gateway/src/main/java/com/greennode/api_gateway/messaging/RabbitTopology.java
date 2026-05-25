package com.greennode.api_gateway.messaging;

public final class RabbitTopology {
  public static final String RESULT_EXCHANGE = "worker.results";
  public static final String RESULT_QUEUE = "api.job-results.queue";
  public static final String RESULT_RETRY_EXCHANGE = "worker.results.retry";
  public static final String RESULT_RETRY_ROUTING_KEY_PREFIX = "api.job-results.retry.";
  public static final String RESULT_DLQ = "api.job-results.dlq";
  public static final String RESULT_DLQ_EXCHANGE = "worker.results.dlx";
  public static final String RESULT_DLQ_ROUTING_KEY = "api.job-results.failed";
  public static final String ATTEMPT_HEADER = "x-task-engine-attempt";

  private RabbitTopology() {}
}
