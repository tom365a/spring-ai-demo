# 启动/停止 8080 上的正式演示实例。
# 真实 KIMI_API_KEY 从 .env 读出后只写进子进程的环境块，不回显、不写日志、不作为命令行参数。
param(
    [int]$Port = 8080,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Stop-App([int]$targetPort) {
    $owners = @(Get-NetTCPConnection -LocalPort $targetPort -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    try { Invoke-RestMethod -Uri "http://127.0.0.1:$targetPort/actuator/shutdown" -Method Post -TimeoutSec 20 | Out-Null } catch { }
    foreach ($owner in $owners) {
        $running = Get-Process -Id $owner -ErrorAction SilentlyContinue
        if ($running) { $running.WaitForExit(20000) | Out-Null; if (!$running.HasExited) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue } }
    }
}

if ($Stop) { Stop-App $Port; Write-Host "App on $Port stopped."; exit 0 }
Stop-App $Port

# 只解析 .env 取值，绝不 dot-source、绝不打印值。
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
if ([string]::IsNullOrWhiteSpace($settings['KIMI_API_KEY'])) { throw '.env 里没有配置 KIMI_API_KEY，无法启动 kimi profile。' }

# 本地嵌入模型不随仓库分发，缺了应用起不来。已存在则秒过。
& (Join-Path $PSScriptRoot 'fetch-model.ps1')

$logFile = Join-Path $projectRoot '.tools/app-8080.log'
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = Join-Path $projectRoot '.tools/apache-maven-3.9.9/bin/mvn.cmd'
$startInfo.Arguments = '-B -o "-Dmaven.repo.local=' + (Join-Path $projectRoot '.tools/m2') + '" -f "' + (Join-Path $projectRoot 'apps/java-gateway/pom.xml') + '" spring-boot:run "-Dspring-boot.run.profiles=local,kimi" "-Dspring-boot.run.arguments=--server.port=' + $Port + ' --management.endpoint.shutdown.access=unrestricted --management.endpoints.web.exposure.include=health,info,shutdown"'
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.WorkingDirectory = $projectRoot
$startInfo.EnvironmentVariables['JAVA_HOME'] = Join-Path $projectRoot '.tools/jdk-21.0.12.1+1'
# 机密只进子进程环境块。
$startInfo.EnvironmentVariables['KIMI_API_KEY'] = $settings['KIMI_API_KEY']
if ($settings['KIMI_BASE_URL']) { $startInfo.EnvironmentVariables['KIMI_BASE_URL'] = $settings['KIMI_BASE_URL'] }
if ($settings['KIMI_MODEL']) { $startInfo.EnvironmentVariables['KIMI_MODEL'] = $settings['KIMI_MODEL'] }

$process = [System.Diagnostics.Process]::Start($startInfo)
$writer = [System.IO.StreamWriter]::new($logFile, $false, [System.Text.UTF8Encoding]::new($false))
$writer.AutoFlush = $true
$onOutput = { if ($EventArgs.Data -ne $null) { $Event.MessageData.WriteLine($EventArgs.Data) } }
Register-ObjectEvent -InputObject $process -EventName OutputDataReceived -Action $onOutput -MessageData $writer | Out-Null
Register-ObjectEvent -InputObject $process -EventName ErrorDataReceived -Action $onOutput -MessageData $writer | Out-Null
$process.BeginOutputReadLine(); $process.BeginErrorReadLine()

$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline -and !$process.HasExited) {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
        if ($health.status -eq 'UP') { Write-Host "App ready on $Port (maven pid $($process.Id)), log $logFile"; exit 0 }
    } catch { }
    Start-Sleep -Milliseconds 500
}
Write-Host 'App failed to become ready. Log tail:'
if (Test-Path $logFile) { Get-Content -LiteralPath $logFile -Tail 30 }
exit 1
