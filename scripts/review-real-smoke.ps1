param([string]$BaseUrl = 'http://127.0.0.1:8081')
$ErrorActionPreference = 'Stop'
function Get-Review { Invoke-RestMethod "$BaseUrl/api/review" }
function Post-Review([string]$path, $body = @{}) { Invoke-RestMethod "$BaseUrl/api/review/$path" -Method Post -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))) -ContentType 'application/json' -TimeoutSec 90 }
$prepared = Post-Review 'prepare'
if ($prepared.status -ne 'READY' -or $prepared.total -lt 1 -or $prepared.total -gt 3) { throw ('Review not ready: ' + $prepared.status + ' ' + $prepared.error) }
if ($prepared.current) { throw 'Question was exposed before starting.' }
$again = Post-Review 'prepare'
if ($again.total -ne $prepared.total) { throw 'Review was not reused.' }
$started = Post-Review 'start'
$id = $started.review.current.id
$hintRequest = @{ requestId = [Guid]::NewGuid().ToString() }
$hint = Post-Review "questions/$id/hint" $hintRequest
if ($hint.review.current.status -ne 'ACTIVE' -or $hint.messages.Count -ne 2) { throw 'Hint flow failed.' }
$duplicateHint = Post-Review "questions/$id/hint" $hintRequest
if ($duplicateHint.messages[-1].id -ne $hint.messages[-1].id) { throw 'Hint request was not idempotent.' }
$answerRequest = @{ requestId = [Guid]::NewGuid().ToString(); content = '소수의 이벤트 루프로 여러 요청을 처리하므로 블로킹하면 다른 요청도 기다립니다. 비동기 DB와 HTTP 호출을 사용합니다.' }
$answer = Post-Review "questions/$id/answer" $answerRequest
if ($answer.review.current.status -ne 'ANSWERED') { throw 'Answer flow failed.' }
$duplicate = Post-Review "questions/$id/answer" $answerRequest
if ($duplicate.messages[-1].id -ne $answer.messages[-1].id) { throw 'Answer was not idempotent.' }
$restored = Get-Review
if ($restored.current.id -ne $id -or $restored.current.status -ne 'ANSWERED') { throw 'Stored review state not restored.' }
foreach ($value in @($prepared, $started, $hint, $answer, $restored)) {
  $serialized = $value | ConvertTo-Json -Depth 10
  if ($serialized -match '"expectedAnswer"|"sourceMessageIds"|"sourceIds"') { throw 'Private answer or evidence metadata exposed.' }
}
[PSCustomObject]@{ actualOpenAI = $true; targetDate = $restored.targetDate; questions = $restored.total; generation = $true; hint = $true; feedback = $true; idempotent = $true; restoration = $true; privateAnswerHidden = $true } | ConvertTo-Json
