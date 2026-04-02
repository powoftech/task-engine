// @ts-check
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

const e2eProcessingTime = new Trend("e2e_processing_time");

export const options = {
  stages: [
    { duration: "30s", target: 50 }, // Warm-up: Ensure system is stable
    { duration: "1m", target: 200 }, // High Load: Push to Tomcat's default thread limit
    { duration: "2m", target: 500 }, // Stress: Push beyond HikariCP connection limits
    { duration: "2m", target: 1000 }, // Breaking Point: Exhaust EC2 resources
    { duration: "30s", target: 0 }, // Recovery: See if the system gracefully recovers
  ],
  thresholds: {
    http_req_duration: ["p(95)<30"],
    http_req_failed: ["rate<0.01"],
    e2e_processing_time: ["p(95)<40000"],
  },
};

export default function () {
  const baseUrl = __ENV.API_URL || "http://localhost:8080";
  const url = `${baseUrl}/api/v1/jobs`;

  const randomComplexity = 1;

  const payload = JSON.stringify({
    taskType: "matrix_multiplication",
    complexity: randomComplexity,
  });

  const params = {
    headers: {
      "Content-Type": "application/json",
    },
    timeout: "10s",
  };

  const response = http.post(url, payload, params);

  const isSuccessful = check(response, {
    "is status 202": (r) => r.status === 202,
  });

  if (isSuccessful) {
    const body = JSON.parse(response.body?.toString() || "{}");
    const jobId = body.id;

    let status = "PENDING";
    let attempts = 0;
    let backoff = 2;
    const startTime = Date.now();

    while (status !== "COMPLETED" && attempts < 15) {
      sleep(backoff);
      attempts++;
      backoff = Math.min(backoff * 1.5, 10);

      const getResponse = http.get(`${url}/${jobId}`);
      if (getResponse.status === 200) {
        const getBody = JSON.parse(getResponse.body?.toString() || "{}");
        status = getBody.status;
      }
    }

    if (status === "COMPLETED") {
      const endTime = Date.now();
      e2eProcessingTime.add(endTime - startTime);
    }
  }

  sleep(0.1);
}
