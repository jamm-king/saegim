$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
docker compose up -d --wait mysql
if ($LASTEXITCODE -ne 0) { throw 'MySQL could not start.' }
Get-Content -LiteralPath "$PSScriptRoot/review-test-db.sql" -Raw | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root'
if ($LASTEXITCODE -ne 0) { throw 'Test schema setup failed.' }
docker build --target build -t saegim-api-tests ./apps/api
if ($LASTEXITCODE -ne 0) { throw 'Test image build failed.' }
$localEnv = [IO.File]::ReadAllText((Join-Path $projectRoot '.env'))
$dbMatch = [regex]::Match($localEnv, '(?m)^DB_PASSWORD=(.*)$')
$previousPassword = $env:DB_PASSWORD
try {
  $env:DB_PASSWORD = $dbMatch.Groups[1].Value.Trim().Trim('"').Trim("'")
  New-Item -ItemType Directory -Path apps/api/build -Force | Out-Null
  docker run --rm --network saegim_default -e RUN_REVIEW_INTEGRATION=true -e 'DB_URL=r2dbc:mysql://mysql:3306/saegim_review_test?serverZoneId=UTC' -e DB_USER=saegim -e DB_PASSWORD --mount "type=bind,source=$projectRoot\apps\api,target=/workspace" --mount 'type=volume,source=saegim-review-test-gradle,target=/root/.gradle' saegim-api-tests sh -c 'chmod +x gradlew && ./gradlew --no-daemon test --rerun-tasks'
  if ($LASTEXITCODE -ne 0) { throw 'Review integration tests failed. See apps/api/build/reports/tests/test.' }
} finally {
  $env:DB_PASSWORD = $previousPassword
}
