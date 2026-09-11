# Launches an isolated backend for the RC14 live-provider check ONLY.
# It is the one launcher that hands the real KIMI_API_KEY to the child process: the value is read from .env
# into the child's environment block and is never echoed, logged, or passed as a Maven/JVM argument.
# Still isolated: own port, own H2 file database, own upload dir. It does not touch the 8080 user application.
param(
    [int]$Port = 18082,
    [string]$DataDir = '.tools/qa-live-run',
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
function Stop-LiveBackend([int]$targetPort) {
    $owners = @(Get-NetTCPConnection -LocalPort $targetPort -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    try { Invoke-RestMethod -Uri "http://127.0.0.1:$targetPort/actuator/shutdown" -Method Post -TimeoutSec 20 | Out-Null } catch { }
    foreach ($owner in $owners) {
        $running = Get-Process -Id $owner -ErrorAction SilentlyContinue
        if ($running) { $running.WaitForExit(20000) | Out-Null; if (!$running.HasExited) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue } }
    }
}
if ($Stop) { Stop-LiveBackend $Port; Write-Host "Live backend on $Port stopped."; exit 0 }
Stop-LiveBackend $Port

# Parse .env for values only. Never dot-source it, never print a value.
$settings = @{}
foreach ($line in [System.IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
    $trimmed = $line.Trim()
    if (!$trimmed -or $trimmed.StartsWith('#')) { continue }
    if ($trimmed -match '^(?:export\s+)?([A-Z][A-Z0-9_]*)\s*=\s*(.*)$') {
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        $settings[$Matches[1]] = $value
    }
}
if ([string]::IsNullOrWhiteSpace($settings['KIMI_API_KEY'])) { throw 'KIMI_API_KEY is not configured in .env; the live check cannot run.' }

$java = Join-Path $projectRoot '.tools/jdk-21.0.12.1+1/bin/java.exe'
$classpathFile = Join-Path $projectRoot '.tools/classpath.txt'
$classpath = (Join-Path $projectRoot 'apps/java-gateway/target/classes') + ';' + [System.IO.File]::ReadAllText($classpathFile).Trim()
$dataRoot = Join-Path $projectRoot $DataDir
New-Item -ItemType Directory -Force -Path $dataRoot, (Join-Path $dataRoot 'uploads'), (Join-Path $dataRoot 'tmp'), (Join-Path $dataRoot 'no-documents') | Out-Null
$database = 'jdbc:h2:file:' + ((Join-Path $dataRoot 'live').Replace('\', '/')) + ';MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE'
$arguments = @(
    "-Djava.io.tmpdir=$(Join-Path $dataRoot 'tmp')", '-cp', $classpath, 'com.demo.cs.CustomerServiceApplication',
    "--server.port=$Port", '--spring.profiles.active=local,kimi', "--spring.datasource.url=$database",
    '--spring.datasource.username=sa', '--spring.datasource.password=',
    "--app.upload-dir=$(Join-Path $dataRoot 'uploads')", "--app.knowledge-sample-dir=$(Join-Path $dataRoot 'no-documents')",
    '--app.vector.backend=lexical', '--spring.ai.model.embedding=none',
    '--management.endpoint.health.probes.enabled=true', '--management.endpoint.shutdown.access=unrestricted',
    '--management.endpoints.web.exposure.include=health,shutdown',
    "--logging.file.name=$(Join-Path $dataRoot 'backend.log')"
)
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $java
# Windows PowerShell 5.1 runs on .NET Framework, which has no ArgumentList/Environment on ProcessStartInfo.
$startInfo.Arguments = ($arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.WorkingDirectory = $projectRoot
# Secrets go in the child's environment block only.
$startInfo.EnvironmentVariables['KIMI_API_KEY'] = $settings['KIMI_API_KEY']
$startInfo.EnvironmentVariables['KIMI_BASE_URL'] = $(if ($settings['KIMI_BASE_URL']) { $settings['KIMI_BASE_URL'] } else { 'https://api.moonshot.cn' })
$startInfo.EnvironmentVariables['KIMI_MODEL'] = $(if ($settings['KIMI_MODEL']) { $settings['KIMI_MODEL'] } else { 'kimi-k3' })
$startInfo.EnvironmentVariables['APP_RESOURCE_MASTER_KEY'] = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
$startInfo.EnvironmentVariables['TEMP'] = Join-Path $dataRoot 'tmp'
$startInfo.EnvironmentVariables['TMP'] = Join-Path $dataRoot 'tmp' 
$process = [System.Diagnostics.Process]::Start($startInfo)
$process.BeginOutputReadLine(); $process.BeginErrorReadLine()
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline -and !$process.HasExited) {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 2
        if ($health.status -eq 'UP') { Write-Host "Live backend ready on $Port (pid $($process.Id)), data $dataRoot"; exit 0 }
    } catch { }
    Start-Sleep -Milliseconds 300
}
Write-Host 'Live backend failed to become ready.'; Get-Content -LiteralPath (Join-Path $dataRoot 'backend.log') -Tail 40; exit 1
