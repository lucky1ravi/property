# Builds and starts every backend service locally, and tells you plainly if anything is wrong.
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1
#   -SkipBuild   start the jars that are already built (no Maven)
#   -Only a,b    build and start just these services (identity-service is always included)
# Postgres runs on host port 5433 so it never clashes with a PostgreSQL already installed on your machine.
# Prerequisites: Docker Desktop (for Postgres), JDK 21+, Maven 3.9+.
param([switch]$SkipBuild, [string[]]$Only = @())

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$logs = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null

function Fail($message) { Write-Host ""; Write-Host "FAILED: $message" -ForegroundColor Red; exit 1 }
function Step($message) { Write-Host ""; Write-Host "== $message" -ForegroundColor Cyan }

# service -> port. Identity first (it owns the database migrations), the gateway last.
$services = [ordered]@{
    "identity-service"    = 8081; "leasing-service" = 8082; "billing-service" = 8083; "maintenance-service" = 8084
    "workflow-service"    = 8085; "ai-service" = 8086; "document-service" = 8087; "api-gateway" = 8080
}
if ($Only.Count -gt 0) {
    $keep = @("identity-service") + $Only
    foreach ($k in @($services.Keys)) { if ($keep -notcontains $k) { $services.Remove($k) } }
}

# --- 1. Find a JDK 21+ ------------------------------------------------------
# The shell's JAVA_HOME is often an older JDK; Maven then fails with "release version 21 not supported".
function JdkMajor($home_) {
    # Read the JDK's release file: `java -version` writes to stderr, which PowerShell 5.1 turns into an error.
    $release = Join-Path $home_ "release"
    if (-not (Test-Path (Join-Path $home_ "bin\java.exe")) -or -not (Test-Path $release)) { return 0 }
    $line = Get-Content $release | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
    if ("$line" -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}
Step "Checking Java"
$jdk = $null
$candidates = @($env:JAVA_HOME) + @(Get-ChildItem "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending | ForEach-Object FullName)
foreach ($c in $candidates) { if ($c -and (JdkMajor $c) -ge 21) { $jdk = $c; break } }
if (-not $jdk) { Fail "No JDK 21 or newer found. Install one and set JAVA_HOME (currently '$env:JAVA_HOME')." }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
$java = Join-Path $jdk "bin\java.exe"
Write-Host "Using JDK $(JdkMajor $jdk) at $jdk"

# --- 2. Stop whatever is already running --------------------------------------
# A running jar is locked by Windows, so it can't be rebuilt, and a second copy can't take the same port.
Step "Stopping running services"
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -match "app\.jar" } | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host "stopped $($_.ProcessId)"
}
Start-Sleep -Seconds 2
foreach ($name in $services.Keys) {
    $busy = Get-NetTCPConnection -LocalPort $services[$name] -State Listen -ErrorAction SilentlyContinue
    if ($busy) { Fail "Port $($services[$name]) ($name) is in use by process $($busy[0].OwningProcess), which is not one of ours. Stop it and run this again." }
}

# --- 3. Database -------------------------------------------------------------
Step "Starting Postgres"
$ErrorActionPreference = "Continue"
docker compose -f infra/docker-compose.yml up -d 2>&1 | ForEach-Object { "$_" } | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "Docker could not start Postgres. Is Docker Desktop running?" }
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    docker exec propertyos-db pg_isready -U postgres 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep 1
}
$ErrorActionPreference = "Stop"
if (-not $ready) { Fail "Postgres did not become ready within 60 seconds." }
Write-Host "Postgres is ready on port 5433"

# --- 4. Build ----------------------------------------------------------------
if ($SkipBuild) {
    Write-Host "Skipping build (-SkipBuild)"
} else {
    Step "Building backend (full log: logs\build.log)"
    $buildLog = Join-Path $logs "build.log"
    $ErrorActionPreference = "Continue"
    Push-Location (Join-Path $root "backend")
    # Test sources are not compiled here: a broken test must not stop you running the app. CI runs the tests.
    # `clean` matters: an incremental build can reuse stale classes and produce a jar that crashes with NoClassDefFoundError.
    Remove-Item $buildLog -ErrorAction SilentlyContinue
    mvn -B clean install "-Dmaven.test.skip=true" 2>&1 | ForEach-Object { "$_" } | ForEach-Object { Add-Content -Path $buildLog -Value $_ -Encoding UTF8; $_ } | ForEach-Object { if ($_ -match '^\[(ERROR|WARNING)\]|BUILD|Reactor Summary|SUCCESS|FAILURE') { Write-Host $_ } }
    $mvnExit = $LASTEXITCODE
    Pop-Location
    $ErrorActionPreference = "Stop"
    if ($mvnExit -ne 0) { Fail "The build failed (exit $mvnExit). Nothing was started, so you are not running stale code. See logs\build.log." }
    Write-Host "Build succeeded"
}

# --- 5. Settings --------------------------------------------------------------
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

# --- 6. Start, and confirm each service is really up ---------------------------
function TailOf($file, $n = 15) { if (Test-Path $file) { (Get-Content $file -Tail $n) -join "`n" } else { "(no log)" } }

function StartService($name) {
    $jar = Join-Path $root "backend\$name\target\app.jar"
    if (-not (Test-Path $jar)) { Fail "$name has no target\app.jar. Run without -SkipBuild." }
    $dir = Join-Path $root "backend\$name"
    $out = Join-Path $logs "$name.log"; $err = Join-Path $logs "$name.err.log"
    Remove-Item $out, $err -ErrorAction SilentlyContinue
    return Start-Process -FilePath $java -ArgumentList "-jar", "target\app.jar" -WorkingDirectory $dir -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
}

function WaitHealthy($name, $proc, $port, $timeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            Write-Host ""; Write-Host "$name exited with code $($proc.ExitCode). Last log lines:" -ForegroundColor Red
            Write-Host (TailOf (Join-Path $logs "$name.err.log")); Write-Host (TailOf (Join-Path $logs "$name.log"))
            return $false
        }
        try {
            $r = Invoke-WebRequest "http://localhost:$port/actuator/health" -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Write-Host ""; Write-Host "$name did not report healthy within $timeoutSec seconds. Last log lines:" -ForegroundColor Red
    Write-Host (TailOf (Join-Path $logs "$name.log"))
    return $false
}

Step "Starting services"
$results = [ordered]@{}
$procs = [ordered]@{}
foreach ($name in $services.Keys) {
    $procs[$name] = StartService $name
    Write-Host "started $name (pid $($procs[$name].Id))"
    if ($name -eq "identity-service") {
        # Identity applies the database migrations; the others start once it is healthy.
        $results[$name] = WaitHealthy $name $procs[$name] $services[$name]
        if (-not $results[$name]) { Fail "identity-service did not start, so the rest were not started." }
    }
}
foreach ($name in $services.Keys) {
    if ($name -eq "identity-service") { continue }
    $results[$name] = WaitHealthy $name $procs[$name] $services[$name]
}

# --- 7. Summary ---------------------------------------------------------------
Step "Summary"
$bad = 0
foreach ($name in $services.Keys) {
    if ($results[$name]) { Write-Host ("  UP    {0,-22} http://localhost:{1}" -f $name, $services[$name]) -ForegroundColor Green }
    else { Write-Host ("  DOWN  {0,-22} see logs\{0}.log and logs\{0}.err.log" -f $name) -ForegroundColor Red; $bad++ }
}
Write-Host ""
if ($bad -gt 0) { Fail "$bad service(s) are not running." }
Write-Host "All services are up. Gateway: http://localhost:8080   Stop them with: .\scripts\stop-local.ps1" -ForegroundColor Green
