param(
  [string]$ApiUrl = "http://localhost:8080",
  [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"

$payload = @{
  taskType = "matrix_multiplication"
  complexity = 5
  clientRequestId = "smoke-$([Guid]::NewGuid())"
} | ConvertTo-Json

$job = Invoke-RestMethod -Method Post -Uri "$ApiUrl/api/v1/jobs" -ContentType "application/json" -Body $payload
$duplicate = Invoke-WebRequest -Method Post -Uri "$ApiUrl/api/v1/jobs" -ContentType "application/json" -Body $payload
if ($duplicate.StatusCode -ne 200) {
  throw "Expected duplicate submit to return 200, got $($duplicate.StatusCode)"
}

$listed = Invoke-RestMethod -Method Get -Uri "$ApiUrl/api/v1/jobs?clientRequestId=$($job.clientRequestId)&size=1"
if ($listed.content.Count -lt 1) {
  throw "Expected submitted job to appear in list endpoint"
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$sawProcessing = $false

while ((Get-Date) -lt $deadline) {
  $current = Invoke-RestMethod -Method Get -Uri "$ApiUrl/api/v1/jobs/$($job.id)"
  if ($current.status -eq "PROCESSING") {
    $sawProcessing = $true
  }
  if ($current.status -eq "COMPLETED") {
    if (-not $sawProcessing) {
      throw "Job $($job.id) completed before smoke test observed PROCESSING"
    }
    Write-Host "Smoke test passed for job $($job.id)"
    break
  }
  if ($current.status -eq "FAILED" -or $current.status -eq "CANCELLED") {
    throw "Job $($job.id) ended with status $($current.status): $($current.failureMessage)"
  }
  Start-Sleep -Seconds 2
}

if ((Get-Date) -ge $deadline) {
  throw "Timed out waiting for job $($job.id) to complete"
}

$cancelPayload = @{
  taskType = "matrix_multiplication"
  complexity = 10
  clientRequestId = "smoke-cancel-$([Guid]::NewGuid())"
} | ConvertTo-Json

$cancelJob = Invoke-RestMethod -Method Post -Uri "$ApiUrl/api/v1/jobs" -ContentType "application/json" -Body $cancelPayload
$cancelled = Invoke-RestMethod -Method Post -Uri "$ApiUrl/api/v1/jobs/$($cancelJob.id)/cancel"
if ($cancelled.status -ne "CANCELLED") {
  throw "Expected cancelled job, got $($cancelled.status)"
}

Start-Sleep -Seconds 12
$afterLateResult = Invoke-RestMethod -Method Get -Uri "$ApiUrl/api/v1/jobs/$($cancelJob.id)"
if ($afterLateResult.status -ne "CANCELLED") {
  throw "Late worker result changed cancelled job to $($afterLateResult.status)"
}

Write-Host "Cancellation smoke test passed for job $($cancelJob.id)"
