# Launches an isolated QA backend: separate port, separate H2 file database, synthetic keys only.
# It never reads the repository .env and never touches the 8080 user application.
param(
    [int]$Port = 18080,
    [string]$DataDir = '.tools/qa-resource-run',
    [switch]$Fresh,
    [switch]$Stop,
    # Seeded model resources are pointed here. Default is a dead loopback port so nothing can reach a real provider;
    # pass the owned fixture port to exercise model calls offline.
    [string]$ModelBaseUrl = 'http://127.0.0.1:1',
    # Empty by default so the isolated run starts with no knowledge documents; point at samples/knowledge for
    # the legacy retrieval regression.
    [string]$KnowledgeDir,
    # 检索后端：simple = 本地 ONNX 语义向量（默认，与产品一致）；lexical = 关键词兜底
    [ValidateSet('simple','lexical')]
    [string]$Vector = 'simple'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$java = Join-Path $projectRoot '.tools/jdk-21.0.12.1+1/bin/java.exe'
function Stop-QaBackend([int]$targetPort) {
    $owners = @(Get-NetTCPConnection -LocalPort $targetPort -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    try { Invoke-RestMethod -Uri "http://127.0.0.1:$targetPort/actuator/shutdown" -Method Post -TimeoutSec 20 | Out-Null } catch { }
    foreach ($owner in $owners) {
        $running = Get-Process -Id $owner -ErrorAction SilentlyContinue
        if ($running) { $running.WaitForExit(20000) | Out-Null; if (!$running.HasExited) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue } }
    }
}
if ($Stop) { Stop-QaBackend $Port; Write-Host "QA backend on $Port stopped."; exit 0 }
Stop-QaBackend $Port
$pom = Join-Path $projectRoot 'apps/java-gateway/pom.xml'
$classpathFile = Join-Path $projectRoot '.tools/classpath.txt'
# A classpath cached before a dependency change fails at runtime as NoClassDefFoundError, so refresh it when the pom is newer.
if (!(Test-Path -LiteralPath $classpathFile) -or (Get-Item $pom).LastWriteTimeUtc -gt (Get-Item $classpathFile).LastWriteTimeUtc) {
    $env:JAVA_HOME = Join-Path $projectRoot '.tools/jdk-21.0.12.1+1'
    & (Join-Path $projectRoot '.tools/apache-maven-3.9.9/bin/mvn.cmd') -B -o "-Dmaven.repo.local=$(Join-Path $projectRoot '.tools/m2')" -f $pom dependency:build-classpath "-Dmdep.outputFile=$classpathFile" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to rebuild the dependency classpath.' }
}
$classpath = (Join-Path $projectRoot 'apps/java-gateway/target/classes') + ';' + [System.IO.File]::ReadAllText($classpathFile).Trim()
$dataRoot = Join-Path $projectRoot $DataDir
if ($Fresh -and (Test-Path -LiteralPath $dataRoot)) {
    # 刚退出的 JVM 可能还锁着解压出来的 onnxruntime 原生库，等一下再删；
    # 临时目录删不掉不影响隔离（数据库和上传目录才是要清的）。
    for ($i = 0; $i -lt 5; $i++) {
        Remove-Item -Recurse -Force -LiteralPath $dataRoot -ErrorAction SilentlyContinue
        if (-not (Test-Path -LiteralPath $dataRoot)) { break }
        Start-Sleep -Milliseconds 600
    }
    if (Test-Path -LiteralPath $dataRoot) {
        Get-ChildItem -LiteralPath $dataRoot -Force -Exclude 'tmp' | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
}
New-Item -ItemType Directory -Force -Path $dataRoot, (Join-Path $dataRoot 'uploads'), (Join-Path $dataRoot 'tmp'), (Join-Path $dataRoot 'no-documents') | Out-Null
$database = 'jdbc:h2:file:' + ((Join-Path $dataRoot 'qa').Replace('\', '/')) + ';MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE'
$arguments = @(
    "-Djava.io.tmpdir=$(Join-Path $dataRoot 'tmp')", '-cp', $classpath, 'com.demo.cs.CustomerServiceApplication',
    "--server.port=$Port", '--spring.profiles.active=local,kimi', "--spring.datasource.url=$database",
    '--spring.datasource.username=sa', '--spring.datasource.password=',
    '--KIMI_API_KEY=qa-offline-resource-key', '--OPENAI_API_KEY=qa-offline-resource-key',
    "--KIMI_BASE_URL=$ModelBaseUrl", "--OPENAI_BASE_URL=$ModelBaseUrl",
    "--app.upload-dir=$(Join-Path $dataRoot 'uploads')", "--app.knowledge-sample-dir=$(if ($KnowledgeDir) { [System.IO.Path]::GetFullPath((Join-Path $projectRoot $KnowledgeDir)) } else { Join-Path $dataRoot 'no-documents' })",
    "--app.vector.backend=$Vector", '--spring.ai.model.embedding=none',
    '--APP_RESOURCE_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    '--management.endpoint.health.probes.enabled=true', '--management.endpoint.shutdown.access=unrestricted',
    '--management.endpoints.web.exposure.include=health,shutdown',
    # Spring's file appender flushes per event; the redirected stdout stream does not, so diagnose from this file.
    "--logging.file.name=$(Join-Path $dataRoot 'backend.log')"
)
$log = Join-Path $dataRoot 'backend.log'
$process = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $dataRoot 'backend.out.log') -RedirectStandardError (Join-Path $dataRoot 'backend.err.log')
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline -and !$process.HasExited) {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 2
        if ($health.status -eq 'UP') { Write-Host "QA backend ready on $Port (pid $($process.Id)), data $dataRoot"; exit 0 }
    } catch { }
    Start-Sleep -Milliseconds 300
}
Write-Host 'QA backend failed to become ready.'; Get-Content -LiteralPath $log -Tail 40; exit 1
