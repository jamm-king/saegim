param([string]$BaseUrl = 'http://127.0.0.1:3000', [string]$RequestId = [Guid]::NewGuid().ToString())
$ErrorActionPreference = 'Stop'

$settings = Invoke-RestMethod "$BaseUrl/api/settings"
if (-not $settings.configured -or $settings.mock) { throw 'OpenAI key is not configured, or mock mode is enabled.' }
$request = @{ requestId = $RequestId; content = 'WebFlux의 이벤트 루프를 차단하면 왜 문제가 되는지 한국어로 두 문장으로 설명해 줘.' }
$body = $request | ConvertTo-Json -Compress
$turn = Invoke-RestMethod "$BaseUrl/api/chat" -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json' -TimeoutSec 90
if ($turn.user.status -ne 'COMPLETE' -or [string]::IsNullOrWhiteSpace($turn.assistant.content)) { throw 'Turn did not complete.' }
$duplicate = Invoke-RestMethod "$BaseUrl/api/chat" -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json' -TimeoutSec 90
if ($duplicate.user.id -ne $turn.user.id -or $duplicate.assistant.id -ne $turn.assistant.id) { throw 'Duplicate request saved duplicate messages.' }
$page = Invoke-RestMethod "$BaseUrl/api/messages"
if (@($page.messages | Where-Object id -eq $turn.assistant.id).Count -ne 1) { throw 'Saved response was not returned in history.' }
$retried = Invoke-RestMethod "$BaseUrl/api/messages/$($turn.user.id)/retry" -Method Post -Body '{}' -ContentType 'application/json'
if ($retried.assistant.id -ne $turn.assistant.id) { throw 'Completed retry was not idempotent.' }
$conflict = @{ requestId = $request.requestId; content = 'different content' } | ConvertTo-Json -Compress
try {
  Invoke-RestMethod "$BaseUrl/api/chat" -Method Post -Body $conflict -ContentType 'application/json' | Out-Null
  throw 'Expected conflict.'
} catch {
  if ([int]$_.Exception.Response.StatusCode -ne 409) { throw }
}
foreach ($invalidContent in @('   ', ('x' * 6001))) {
  $invalid = @{ requestId = [Guid]::NewGuid().ToString(); content = $invalidContent } | ConvertTo-Json -Compress
  try {
    Invoke-RestMethod "$BaseUrl/api/chat" -Method Post -Body $invalid -ContentType 'application/json' | Out-Null
    throw 'Expected validation failure.'
  } catch {
    if ([int]$_.Exception.Response.StatusCode -ne 400) { throw }
  }
}
[PSCustomObject]@{ actualOpenAI = $true; model = $settings.model; userId = $turn.user.id; assistantId = $turn.assistant.id; saved = $true; idempotent = $true; validation = $true } | ConvertTo-Json
