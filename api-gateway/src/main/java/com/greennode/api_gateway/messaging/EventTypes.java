package com.greennode.api_gateway.messaging;

public final class EventTypes {
  public static final String JOB_REQUESTED = "job.requested.v1";
  public static final String JOB_PROCESSING = "job.processing.v1";
  public static final String JOB_COMPLETED = "job.completed.v1";
  public static final String JOB_FAILED = "job.failed.v1";

  private EventTypes() {}
}
