# Starts every backend service that has been built. Run from the repo root:  .\scripts\start-local.ps1
# Postgres runs on host port 5433 so it never clashes with a PostgreSQL already installed on your machine.
# Prerequisites: Docker Desktop (for Postgres), JDK 21+, Maven 3.9+, Node 20+.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

docker compose -f infra/docker-compose.yml up -d
Write-Host "Waiting for Postgres..."
for ($i = 0; $i -lt 30; $i++) { docker exec propertyos-db pg_isready -U postgres 2>$null | Out-Null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep 1 }

Push-Location backend
mvn -q -B install -DskipTests
Pop-Location

# Optional settings (AI provider and key, etc.) from .env.local at the repo root. That file is git-ignored.
$envFile = Join-Path $root ".env.local"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and -not $_.TrimStart().StartsWith("#")) {
            Set-Item -Path "env:$($Matches[1])" -Value $Matches[2].Trim('"')
        }
    }
    Write-Host "Loaded settings from .env.local"
}

$env:PGHOST = "localhost"; $env:PGPORT = "5433"; $env:PGDATABASE = "propertyos"
$env:PGUSER = "postgres"; $env:PGPASSWORD = "postgres"

$services = @("identity-service", "leasing-service", "billing-service", "maintenance-service", "workflow-service", "ai-service", "document-service", "api-gateway")
New-Item -ItemType Directory -Force -Path logs | Out-Null
foreach ($s in $services) {
    $jar = "backend\$s\target\app.jar"
    if (-not (Test-Path $jar)) { Write-Host "skip $s (not built yet)"; continue }
    $dir = Join-Path $root "backend\$s"
    Start-Process -FilePath java -ArgumentList "-jar", "target\app.jar" -WorkingDirectory $dir -RedirectStandardOutput (Join-Path $root "logs\$s.log") -RedirectStandardError (Join-Path $root "logs\$s.err.log") -WindowStyle Hidden
    Write-Host "started $s  (log: logs\$s.log)"
}
Write-Host "Gateway: http://localhost:8080   Health: http://localhost:8080/actuator/health"
