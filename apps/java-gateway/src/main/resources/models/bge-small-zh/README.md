# bge-small-zh-v1.5（ONNX int8 量化）

本地中文嵌入模型，供知识检索生成真实语义向量使用。**运行时不请求任何外部嵌入服务。**

| 项 | 值 |
| --- | --- |
| 模型 | BAAI/bge-small-zh-v1.5 |
| 许可证 | MIT |
| ONNX 转换来源 | huggingface.co/Xenova/bge-small-zh-v1.5 |
| 文件 | `model.onnx`（int8 量化，22.9 MB）、`tokenizer.json`（0.42 MB） |
| 向量维度 | 512 |

选量化版而非 fp32（90.5 MB）是为了控制交付包体积；BGE-small 在 int8 下的检索质量损失很小，
对演示级知识库足够。需要更高精度时把 `model.onnx` 换成同仓库的 `onnx/model.onnx` 即可，无需改代码。
