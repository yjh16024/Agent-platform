---
name: example-pdf-processing
description: 示例 Skill——提取 PDF 文本、合并/拆分与表单填写，处理用户上传的 PDF 时使用
version: 1.0.0
license: MIT
allowed-tools:
  - search
---

# PDF 处理（示例 Skill）

## 何时使用

用户要求提取 PDF 文本、合并/拆分 PDF，或填写 PDF 表单时使用本 Skill。

## 操作步骤

1. 先确认文件路径与页数，避免处理超大文件；
2. 扫描件先做旋转/去噪预处理（见 `scripts/`）；
3. 提取文本时保留页码，便于回答时给出引用；
4. 合并/拆分后向用户回报输出路径。

## 约束

- 不改写 PDF 原始内容，除非用户明确要求；
- 无法确认的表单字段要向用户提问，不要猜测填写。
