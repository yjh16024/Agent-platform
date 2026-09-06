# Skills 目录（Agent Skills 开放标准）

本目录是平台的 Skills 存储根，遵循 [Agent Skills 开放标准](https://agentskills.io)：

```
skills/
  <skill-name>/          ← 一个 Skill = 一个目录（目录名即标识）
    SKILL.md             ← 必需：YAML frontmatter（name/description/version/allowed-tools）+ 提示词正文
    scripts/             ← 可选：可执行脚本（按需加载）
    references/          ← 可选：参考文档（如 FORMS.md）
    assets/              ← 可选：模板、图片等静态资源
```

## 怎么用

1. 从网上下载一个 Skill（目录或 .zip），解压后把**整个目录**放进这里；
2. 打开仪表盘「Skills」页 → 点「**扫描同步目录**」，平台会自动识别并入库；
3. 也可以用「上传 Skill」直接传 .zip 或 SKILL.md；
4. 在智能体的「能力 → skillIds」中挂载后，SKILL.md 正文会在运行时注入系统提示词。

> 说明：缺少 `SKILL.md` 的子目录会被扫描跳过（不会报错）。
> 目录由仪表盘管理：删除 Skill 会同时删除这里对应的子目录。
> 本目录默认位于 `./data/skills`，可用环境变量 `SKILLS_DIR` 覆盖。

## SKILL.md 示例

```markdown
---
name: pdf-processing
description: 提取 PDF 文本、填表与合并拆分，处理用户上传的 PDF 时使用
version: 1.0.0
allowed-tools:
  - search
---

# PDF 处理

## 何时使用
用户要求提取 PDF 文本、合并/拆分 PDF 或填写表单时使用。

## 操作步骤
1. 确认文件路径；
2. 使用 scripts/rotate.py 预处理扫描件；
3. 输出纯文本并保留页码引用。
```
