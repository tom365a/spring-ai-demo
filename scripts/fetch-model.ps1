# 拉取本地嵌入模型 bge-small-zh-v1.5（ONNX int8）。
# 模型有 23 MB，不进 git 仓库；首次运行（以及 CI）需要联网执行一次，之后本地缓存不再下载。
# 来源固定在某个 revision 上，并逐个校验 SHA256——上游改文件时宁可失败，也不要静默换掉模型。
param(
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# Windows PowerShell 5.1 跑在 .NET Framework 上，默认还在用 TLS 1.0/1.1，huggingface.co 会直接断流
# （报 "unexpected EOF or 0 bytes from the transport stream"）。必须显式开 TLS 1.2。
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$repo = 'Xenova/bge-small-zh-v1.5'
$revision = '75c43b069aac4d136ba6bc1122f995fedcfd2781'
$targetDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'apps/java-gateway/src/main/resources/models/bge-small-zh'

$files = @(
    @{ Remote = 'onnx/model_quantized.onnx'; Local = 'model.onnx';     Sha = '15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc' },
    @{ Remote = 'tokenizer.json';            Local = 'tokenizer.json'; Sha = '48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26' }
)

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

foreach ($file in $files) {
    $path = Join-Path $targetDir $file.Local
    if (!$Force -and (Test-Path $path)) {
        $have = (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLower()
        if ($have -eq $file.Sha) { Write-Host "已存在且校验通过：$($file.Local)"; continue }
        Write-Host "校验和不符，重新下载：$($file.Local)"
    }
    $url = "https://huggingface.co/$repo/resolve/$revision/$($file.Remote)"
    Write-Host "下载 $($file.Local) ..."
    $tmp = "$path.part"
    try {
        Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    } catch {
        if (Test-Path $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
        throw "下载失败：$url`n$($_.Exception.Message)`n（需要能访问 huggingface.co；内网环境请手动放置模型文件）"
    }
    $got = (Get-FileHash -Path $tmp -Algorithm SHA256).Hash.ToLower()
    if ($got -ne $file.Sha) {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        throw "SHA256 不匹配：$($file.Local)`n期望 $($file.Sha)`n实际 $got"
    }
    Move-Item -LiteralPath $tmp -Destination $path -Force
    Write-Host "完成：$($file.Local)"
}
Write-Host "模型就绪：$targetDir"
