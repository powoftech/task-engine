// @ts-check
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

// Custom metric to track how long the entire asynchronous process takes
const e2eProcessingTime = new Trend("e2e_processing_time");

// 1. Configure the Load Test Stages
export const options = {
  stages: [
    { duration: "30s", target: 50 }, // Warm-up: Ensure system is stable
    { duration: "1m", target: 200 }, // High Load: Push to Tomcat's default thread limit
    { duration: "2m", target: 500 }, // Stress: Push beyond HikariCP connection limits
    { duration: "2m", target: 1000 }, // Breaking Point: Exhaust EC2 resources
    { duration: "30s", target: 0 }, // Recovery: See if the system gracefully recovers
  ],
  thresholds: {
    // Gateway ingestion speed: 95% of POST requests within 30ms
    http_req_duration: ["p(95)<30"],
    http_req_failed: ["rate<0.01"],
    // System-wide E2E processing: 95% of jobs should complete within 40 seconds
    e2e_processing_time: ["p(95)<40000"],
  },
};

// 2. The simulated user behavior
export default function () {
  // The target URL (Use an environment variable so we can change it to the Cloud IP later)
  const baseUrl = __ENV.API_URL || "http://localhost:8080";
  const url = `${baseUrl}/api/v1/jobs`;

  // Fixed complexity to 1 to precisely measure infrastructure overhead
  const randomComplexity = 1;

  const payload = JSON.stringify({
    taskType: "matrix_multiplication",
    complexity: randomComplexity,
  });

  const params = {
    headers: {
      "Content-Type": "application/json",
    },
    timeout: "10s", // Don't hang forever if the API stalls
  };

  // 3. Send the POST request
  const response = http.post(url, payload, params);

  // 4. Verify the response is 202 Accepted
  const isSuccessful = check(response, {
    "is status 202": (r) => r.status === 202,
  });

  if (isSuccessful) {
    const body = JSON.parse(response.body?.toString() || "{}");
    const jobId = body.id;

    // 5. Polling Loop with Exponential Backoff (Protects against Polling Storm)
    let status = "PENDING"; 
    let attempts = 0; 
    let backoff = 2; // Start with 2 seconds
    const startTime = Date.now(); 

    while (status !== "COMPLETED" && attempts < 15) { // Limit to 15 attempts to prevent infinite loops
      sleep(backoff);
      attempts++;
      backoff = Math.min(backoff * 1.5, 10); // Exponential backoff up to 10s

      const getResponse = http.get(`${url}/${jobId}`);
      if (getResponse.status === 200) {
        const getBody = JSON.parse(getResponse.body?.toString() || "{}");
        status = getBody.status;
      }
    }

    // If it successfully processed, record the total E2E time
    if (status === "COMPLETED") {
      const endTime = Date.now();
      e2eProcessingTime.add(endTime - startTime);
    }
  }

  // 6. Short sleep to simulate real user pacing
  sleep(0.1);
}
