param(
  [string]$ApiUrl = "http://localhost:8080",
  [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"

$payload = @{
  taskType = "matrix_multiplication"
  complexity = 1
  clientRequestId = "smoke-$([Guid]::NewGuid())"
} | ConvertTo-Json

$job = Invoke-RestMethod -Method Post -Uri "$ApiUrl/api/v1/jobs" -ContentType "application/json" -Body $payload
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
  $current = Invoke-RestMethod -Method Get -Uri "$ApiUrl/api/v1/jobs/$($job.id)"
  if ($current.status -eq "COMPLETED") {
    Write-Host "Smoke test passed for job $($job.id)"
    exit 0
  }
  if ($current.status -eq "FAILED" -or $current.status -eq "CANCELLED") {
    throw "Job $($job.id) ended with status $($current.status): $($current.failureMessage)"
  }
  Start-Sleep -Seconds 2
}

throw "Timed out waiting for job $($job.id) to complete"
