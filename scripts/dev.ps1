param(
    [ValidateSet('test', 'run', 'package')]
    [string]$Action = 'run',
    [ValidateSet('kimi', 'openai')]
    [string]$Provider,
    [int]$Port = 8080,
    [switch]$Offline,
    [string]$EnvFile,
    [switch]$ValidateConfigOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$selectedProvider = $null
if ($Action -eq 'run') {
    # Parse data only. Never dot-source .env, expand variables or print secret values.
    $configPath = if ($EnvFile) { [System.IO.Path]::GetFullPath($EnvFile) } else { Join-Path $projectRoot '.env' }
    if (Test-Path -LiteralPath $configPath) {
        $configLineNumber = 0
        foreach ($configLine in [System.IO.File]::ReadAllLines($configPath)) {
            $configLineNumber++
            $configText = $configLine.Trim()
            if (!$configText -or $configText.StartsWith('#')) { continue }
            if ($configText -notmatch '^(?:export\s+)?([A-Z][A-Z0-9_]*)\s*=\s*(.*)$') {
                throw "Invalid .env syntax at line $configLineNumber (values are hidden)."
            }
            $configName = $Matches[1]
            $configValue = $Matches[2].Trim()
            if ($configValue.Length -ge 2 -and (($configValue.StartsWith('"') -and $configValue.EndsWith('"')) -or ($configValue.StartsWith("'") -and $configValue.EndsWith("'")))) {
                $configValue = $configValue.Substring(1, $configValue.Length - 2)
            } else { $configValue = ($configValue -replace '\s+#.*$', '').TrimEnd() }
            if ($configName -notmatch '^(LLM_|KIMI_|OPENAI_|EMBEDDING_|SPRING_|APP_|MCP_|RAG_|SESSION_|ROUTE_)') { continue }
            if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($configName, 'Process'))) {
                [Environment]::SetEnvironmentVariable($configName, $configValue, 'Process')
            }
        }
    }
    $selectedProvider = if ($Provider) { $Provider } elseif ($env:LLM_PROVIDER) { $env:LLM_PROVIDER.ToLowerInvariant() } else { 'kimi' }
    if ($selectedProvider -notin @('kimi', 'openai')) { throw 'LLM_PROVIDER must be kimi or openai.' }
    $keyName = if ($selectedProvider -eq 'kimi') { 'KIMI_API_KEY' } else { 'OPENAI_API_KEY' }
    $providerKey = [Environment]::GetEnvironmentVariable($keyName, 'Process')
    if ([string]::IsNullOrWhiteSpace($providerKey) -or $providerKey -match '^sk-(your-key|not-configured|xxx)$') {
        throw "$keyName is required for the selected provider. No fallback provider will be called."
    }
    $env:LLM_PROVIDER = $selectedProvider
    # Keep secrets in the child environment; they never appear in Maven arguments.
    Write-Host "Selected provider: $selectedProvider (credentials loaded; values hidden)."
    if ($ValidateConfigOnly) { exit 0 }
} elseif ($ValidateConfigOnly) {
    throw '-ValidateConfigOnly is supported only with -Action run.'
}
$portableRoot = Join-Path $projectRoot '.tools'
$portableJdk = Get-ChildItem -LiteralPath $portableRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portableJdk) { $env:JAVA_HOME = $portableJdk.FullName }
if (!$env:JAVA_HOME -and !(Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JDK 21 is required. Set JAVA_HOME or extract a portable JDK 21 into .tools.'
}
$portableMaven = Get-ChildItem -LiteralPath $portableRoot -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue | Select-Object -First 1
$mavenCommand = if ($portableMaven) { Join-Path $portableMaven.FullName 'bin/mvn.cmd' } else {
    $foundMaven = Get-Command mvn -ErrorAction SilentlyContinue
    if (!$foundMaven) { throw 'Maven 3.9+ is required. Add it to PATH or extract it into .tools.' }
    $foundMaven.Source
}
# 本地嵌入模型不随仓库分发（23 MB），run 和 test 都要用到；已存在则秒过。
& (Join-Path $PSScriptRoot 'fetch-model.ps1')

$mavenArguments = @('-B')
if ($portableMaven) { $mavenArguments += "-Dmaven.repo.local=$(Join-Path $portableRoot 'm2')" }
if ($Offline) { $mavenArguments += '-o' }
if ($Action -eq 'run') {
    $mavenArguments += @('spring-boot:run', "-Dspring-boot.run.profiles=local,$selectedProvider", "-Dspring-boot.run.arguments=--server.port=$Port")
} else {
    $mavenArguments += $Action
}
Push-Location (Join-Path $projectRoot 'apps/java-gateway')
try {
    & $mavenCommand @mavenArguments
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $buildExitCode
