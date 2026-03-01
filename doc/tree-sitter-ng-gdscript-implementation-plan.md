# tree-sitter-ng + GDScript 解析接入实施基线（维护版）

## 1. 文档目的

- 本文档用于维护 **当前可用能力、长期工程约定、未完成事项与后续执行清单**。
- 本文档不再保留阶段性流水过程（谁在何时做了什么），仅保留对后续工程有持续价值的信息。
- 时间基线：**2026-03-01**（已落地能力与待办以该日期工作区为准）。

## 2. 当前能力基线（已完成）

### 2.1 解析与 Native 加载

- 已完成 `tree-sitter-ng` 绑定接入（`io.github.bonede:tree-sitter`）。
- 已完成 `tree_sitter_gdscript` 动态库符号加载与 ABI 预检。
- 已完成多策略加载链路（含资源目录提取）。
- Windows + Java 25 下端到端解析可用。

核心入口：

- `infra/treesitter/GdLanguageLoader`
- `infra/treesitter/GdLanguageAbiChecker`
- `infra/treesitter/GdParserFacade`

### 2.2 CST 稳定访问层

- 已完成 `CstNodeView` 不可变快照抽象，隔离 `org.treesitter` 直接依赖。
- 已完成 `CstErrorDetector`（`ERROR`/`MISSING` 聚合）。
- mapper 层已只依赖 CST 抽象层。

核心入口：

- `frontend/cst/CstNodeView`
- `frontend/cst/CstAdapter`
- `frontend/cst/CstErrorDetector`

### 2.3 AST 数据模型与 Lowering

- 已完成 AST 顶级模型（record/sealed hierarchy）并拆分为独立文件。
- 已完成核心 lowering（顶层声明、常见语句、核心表达式）。
- 已完成 `map`（容错）与 `mapStrict`（错误即失败）双模式。
- 已完成 span 级诊断承载（`AstDiagnostic` + severity）。

核心入口：

- `frontend/ast/*`
- `frontend/lowering/CstToAstMapper`

### 2.4 AST S-expr 序列化协议

- 已完成 AST -> S-expr 序列化。
- 已完成 S-expr -> AST 严格反序列化（结构/类型校验）。
- 已完成 fixture 脚本级 round-trip 回归测试。

核心入口：

- `frontend/serialize/AstSexprSerializer`
- `frontend/serialize/AstSexprDeserializer`
- `frontend/serialize/AstSexprSchema`

### 2.5 测试基线

已具备如下测试层次：

- 解析层：`GdParserFacadeTest`
- CST 层：`CstAdapterTest`、`CstFixtureScriptsTest`
- Lowering 层：`CstToAstMapperTest`
- 序列化层：`AstSexprSerdeTest`

外部语料：

- `src/test/resources/gdscript`（含来自 `elamaunt/GDShrapt` 的脚本）

## 3. 长期工程约定（保留）

### 3.1 Grammar 与 ABI 约定

- grammar 来源：`PrestonKnopp/tree-sitter-gdscript`。
- 当前基线：`tree-sitter.json` 版本 `6.1.0`、`parser.c LANGUAGE_VERSION 14`。
- Tree-sitter ABI 兼容区间：`MIN_COMPATIBLE=13`，`LANGUAGE_VERSION=15`（由 runtime 常量约束）。
- 规则约定：`..`（open-ended pattern）仅按官方语法尾部使用；中置/前置应视为错误输入。

### 3.2 Native 加载约定

运行时加载优先级（当前实现）：

1. 托管资源目录（`gdparser.gdscript.resourceDir`，默认当前目录 `native`）
2. `java.library.path`
3. 显式库文件（`gdparser.gdscript.nativeLibPath`）
4. 显式目录（`gdparser.gdscript.nativeLibDir`）
5. classpath 临时提取回退

约定：

- 若托管资源目录缺库，允许从 classpath 自动提取。
- 所有失败场景应输出可定位信息（os/arch、尝试路径、失败原因）。

### 3.3 CST/AST 设计约定

- AST 节点必须携带稳定 `Range`（byte + point）。
- mapper 不直接依赖 `TSNode` API，只依赖 `CstNodeView`。
- 不支持节点不直接丢弃，统一降级为 `Unknown*` 并附 warning。
- 结构错误（`ERROR`/`MISSING`）统一进入 error 级诊断。

### 3.4 S-expr 协议约定

当前协议（v1 语义，尚无显式版本头）：

- record：`(<tag> (<field> <value>) ...)`
- list：`(list ...)`
- null：`nil`
- string：支持 `\\n`、`\\r`、`\\t`、`\\"`、`\\\\` 转义

约定：

- 输出必须 canonical（同一 AST 多次序列化文本一致）。
- 反序列化必须严格校验字段与类型（未知字段、重复字段、类型不匹配直接失败）。

### 3.5 测试约定

- 迭代阶段仅跑定向测试类；PR 前再补全量构建。
- fixture 脚本新增/修改时必须同步：
  - CST 结构基线
  - lowering 无 error 基线（或显式声明例外）
  - AST serde round-trip 基线

## 4. 未完成事项（补充）

## 4.1 P0：跨平台运行验收与 CI 固化

现状：Windows 已验证，Linux/macOS 尚未端到端验收。

待完成：

- 在 CI 中补齐 Linux/macOS matrix：native 构建 + 解析测试 + lowering 测试。
- 固化每平台产物命名与装载路径约定。

验收标准：

- 三平台均通过 `GdParserFacadeTest`、`CstFixtureScriptsTest`、`CstToAstMapperTest`、`AstSexprSerdeTest`。

## 4.2 P0：grammar 锁定与升级流程文档化

现状：已有实践但未形成独立、可执行的流程文档。

待完成：

- 明确锁定信息：上游仓库、commit/tag、`parser.c` 关键常量。
- 明确升级步骤：升级分支、语料 diff、诊断差异评估、回滚策略。

验收标准：

- 新成员可仅按文档完成一次 grammar 升级演练。

## 4.3 P1：AST 快照回归（Golden）

现状：已有 round-trip 校验，但缺 `input.gd -> ast.json` 的结构快照。

待完成：

- 增加 AST 快照导出（建议 JSON 稳定字段顺序）。
- 对外部语料建立 golden 基线并接入回归测试。

验收标准：

- grammar 或 mapper 变更能通过快照 diff 快速定位语义变更。

## 4.4 P1：S-expr 协议显式版本化

现状：协议稳定但未显式声明版本头。

待完成：

- 在顶层增加可演进版本标记（如 `ast-v1`）。
- 反序列化器提供版本分派与错误提示。

验收标准：

- 协议演进时可兼容读取旧版本或给出明确迁移错误。

## 4.5 P2：性能与资源基线

现状：功能可用，缺少 parse/lowering/serde 的性能指标与回归阈值。

待完成：

- 建立中等规模语料 benchmark（吞吐、峰值内存、P95 延迟）。
- 在 CI 添加可选性能回归检查（非阻断或阈值阻断）。

验收标准：

- 关键路径有可量化、可对比的历史指标。

## 5. 工程反思（保留）

1. 先做稳定抽象面（CST）再做语义映射（AST）是正确路径，显著降低了底层 API 变更影响面。
2. `Unknown* + AstDiagnostic` 比“遇错即终止”更适合编译器/IDE 双场景，可同时满足容错与严格模式。
3. Native 加载是主要工程复杂度来源，必须将“路径策略 + 错误可观测性 + CI 跨平台验证”视为同一问题。
4. 仅做 round-trip 不能替代语义快照，后续必须补 AST golden 以控制语义回退风险。
5. S-expr 当前可用但需尽快版本化，否则未来协议扩展成本会增大。

## 6. 下一迭代建议（可直接执行）

1. 先完成 P0：跨平台 CI 与 grammar 锁定文档。
2. 再完成 P1：AST golden + S-expr 显式版本化。
3. 最后推进 P2：性能基线与回归阈值。

## 7. 参考资料

- tree-sitter-ng: https://github.com/bonede/tree-sitter-ng
- tree-sitter `api.h`: https://github.com/tree-sitter/tree-sitter/blob/master/lib/include/tree_sitter/api.h
- tree-sitter-gdscript: https://github.com/PrestonKnopp/tree-sitter-gdscript
- tree-sitter-gdscript `tree-sitter.json`: https://github.com/PrestonKnopp/tree-sitter-gdscript/blob/master/tree-sitter.json
- tree-sitter-gdscript `parser.c`: https://github.com/PrestonKnopp/tree-sitter-gdscript/blob/master/src/parser.c
