#!/usr/bin/env bash
# 拉取本地嵌入模型 bge-small-zh-v1.5（ONNX int8）。PowerShell 版见 fetch-model.ps1。
# 模型 23 MB，不进 git 仓库；首次运行需要联网执行一次。revision 与 SHA256 均已固定。
set -euo pipefail

repo="Xenova/bge-small-zh-v1.5"
revision="75c43b069aac4d136ba6bc1122f995fedcfd2781"
target_dir="$(cd "$(dirname "$0")/.." && pwd)/apps/java-gateway/src/main/resources/models/bge-small-zh"
mkdir -p "$target_dir"

fetch() {
  local remote="$1" local_name="$2" want="$3"
  local path="$target_dir/$local_name"
  if [ -f "$path" ] && [ "$(sha256sum "$path" | cut -d' ' -f1)" = "$want" ]; then
    echo "已存在且校验通过：$local_name"; return
  fi
  echo "下载 $local_name ..."
  if ! curl -fsSL -o "$path.part" "https://huggingface.co/$repo/resolve/$revision/$remote"; then
    rm -f "$path.part"
    echo "下载失败（需要能访问 huggingface.co；内网环境请手动放置模型文件）" >&2
    exit 1
  fi
  local got
  got="$(sha256sum "$path.part" | cut -d' ' -f1)"
  if [ "$got" != "$want" ]; then
    rm -f "$path.part"
    echo "SHA256 不匹配：$local_name
期望 $want
实际 $got" >&2
    exit 1
  fi
  mv -f "$path.part" "$path"
  echo "完成：$local_name"
}

fetch "onnx/model_quantized.onnx" "model.onnx"     "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"
fetch "tokenizer.json"            "tokenizer.json" "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26"
echo "模型就绪：$target_dir"
