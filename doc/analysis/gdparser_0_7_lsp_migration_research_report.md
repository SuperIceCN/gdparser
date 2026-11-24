# gdparser 0.7.0 与 GDCC Frontend LSP 基础设施迁移调研

日期：2026-10-07。

本文是调研报告，不是已实施合同，也不修改 `frontend_lsp_foundation_implementation.md` 的现行合同。结论来自文档阅读、两个仓库的源码核对、Godot
上游源码对照及隔离定向测试。生产源码、依赖版本、构建配置和既有测试期望均未修改。

## 1. 结论

**0.7.0 为替换旧解析链路提供了明显更好的基础，但不能仅升级依赖就消除遗留问题，也不能原样切换入口后宣告 LSP 基础设施迁移完成。
**

1. **仅升级版本号没有恢复收益。** 新版本新增独立的纯 Java `GdScriptParserFacade`；原来的
   `infra.treesitter.GdParserFacade`、`CstToAstMapper` 和补全上下文分析器保留不动。GDCC 当前仍显式调用旧入口。
2. **切换解析入口可以改善正常编辑场景的恢复边界。** `self.`、`v.normalized().` 后的下一函数不再被吞掉；原来受限位置的
   receiver 事实可以保留，现有成员候选查询在实测样例中恢复工作。损坏 `if`/`for`/`match` 的健康同函数兄弟仍可分析。
3. **新 AST 恢复政策不满足 GDCC 现行发布合同。** 新入口不产生 `ErrorStatement`、`ErrorExpression` 或
   `MissingAttributeStep`；部分坏构造直接丢弃，另一些却留下外观正常的声明、参数、返回语句或容器。后者绕过现有 skipped
   标注，导致错误语义事实与重复诊断。
4. **补全与 signature help 没有随重写完成。** 补全仍由旧 tree-sitter 路径承担；新入口没有 cursor context、callee 定位或
   active argument index。`super.` 的 receiver 被新入口丢弃，现有 super 成员补全发生实际回归。
5. **语义兼容性不能由 fixture parity 推导。** 新入口移植了部分原先属于 GDCC sema 的检查，改变诊断
   owner；表达式优先级、空枚举、非法开放容器表达式等也改变行为。取消数字符号吸收及 `-2**2 -> -(2**2)` 已接受为预期策略，仅记录为已知
   Godot 差异，不列为缺陷或迁移阻断项。

建议：先补齐恢复信息和诊断 owner 合同，再切换默认解析入口；短期可以继续使用旧路径做范围/上下文分类，但不能把这种混合方案称为已经移除旧解析器的
LSP 限制。新纯 Java 的 cursor context 是完整摆脱旧补全限制的后续必要工作。

后续针对其他缺陷的最小复现、实际 AST、下游事实和分类见
[`gdparser_0_7_pure_java_parser_regressions_report.md`](gdparser_0_7_pure_java_parser_regressions_report.md)
。其中补充了空参数类型的零诊断差异、字典恢复节点范围、错误后缀重绑定及损坏注解元数据投影。

## 2. 调研基线与方法

### 2.1 版本

| 对象                | 本次基线                                                      | 说明                                               |
|-------------------|-----------------------------------------------------------|--------------------------------------------------|
| GDCC              | `432f32ec32b2207db1db3bfb81a559fc5b3d6e9d`                | 当前 `build.gradle.kts:57` 固定 `gdparser:0.6.0`     |
| 同级 gdparser       | `d2c3ef355530a2c87697b912b3c3d49f6a9aa475`                | `build.gradle.kts:10` 的默认版本为 `0.7.0`             |
| gdparser 对照 Godot | `c016f22009aa773f0b2e6fce72baec168746491f`                | `vendor/godot/COMMIT_SHA`，2026-10-05 的 master 基线 |
| GDCC 运行时目标        | Godot `4.5.1`                                             | `doc/gdcc_runtime_lib.md:14,639`                 |
| Godot 稳定版对照       | `4.5.1-stable`，`f62fdbde15035c5576dad93e586201f4d41ef0cb` | 同时检查恢复与运算符关键机制                                   |

同级仓库本地没有 `0.7.0` 标签，已有 `build/libs` 产物仍名为 `0.6.0`。因此本次直接编译当前新入口源码验证，没有把旧 jar 当作
0.7.0，也没有验证远程 JitPack `0.7.0` 工件是否已发布及其内容。

### 2.2 文档范围

首先通过 IDE 列出 `doc` 与 `doc/module_impl` 的四层目录，并分组并行探索文档、GDCC API/解析接入、sema/lowering、gdparser 及
Godot。

主要现行合同：

- `doc/module_impl/common_rules.md` 与 `doc/module_impl/frontend/frontend_rules.md`。
- `frontend_lsp_foundation_implementation.md`，尤其 §2.1/§2.2/§2.4/§2.5 与 §3 的实测限制。
- `diagnostic_manager.md`、`frontend_resolution_pipeline_implementation.md`、
  `frontend_chain_binding_expr_type_implementation.md`。
- `frontend_exact_call_extension_metadata_contract.md`、`frontend_parameter_default_implementation.md`、
  `frontend_lowering_plan.md`。
- `frontend_analysis_inspection_tool_implementation.md`、API/RPC 实现文档。
- 同级 gdparser 的 `doc/godot-java-parser-implementation.md`、`doc/architecture-overview.md`、
  `doc/tree-sitter-ng-gdscript-implementation-plan.md`。

历史分析报告仅用于理解背景，没有把其中尚未实施或已过时的阶段状态当成现行事实。

### 2.3 验证方式及边界

GDCC 原依赖基线通过仓库规定的 `script/run-gradle-targeted-tests.sh` 执行。新入口验证使用 `javac` 和 JUnit Launcher，在
`tmp/probes` 中隔离编译，不修改两个仓库的生产源码与配置。

迁移实验把仅供研究的同名 `GdScriptParserService` 类放在运行时 classpath 最前面，使无参构造改走新入口，复用原来的诊断映射及
`parse.internal` 处理。旧的两参注入构造仅为运行现有失败注入测试而保留，**不是建议生产保留的兼容层**。

共享 AST/CST/lowering 类型仍使用 GDCC 的 0.6.0 工件；这些源码在本地 0.6.0 到当前 gdparser 的差异中未改变。该方式验证新入口与既有
GDCC 管线的行为兼容性，不等价于正式升级工件后的 Gradle 构建、模块路径、打包及发布验收。

所有 native 解压和研究产物落在 GDCC `tmp`，同级 gdparser 保持只读。已有 worktree/staging 内容未修改。

## 3. 现有基础设施真正依赖什么

### 3.1 解析与补全是两条独立调用链

当前语义解析入口：

```text
GdScriptParserService.parseUnit
  -> infra.treesitter.GdParserFacade.parseCstRoot
  -> CstToAstMapper.map
  -> SourceFile + AstDiagnostic
  -> DiagnosticManager / FrontendSourceUnit
```

源码：`src/main/java/gd/script/gdcc/frontend/parse/GdScriptParserService.java:18-59`。

当前补全入口：

```text
FrontendSnapshotCompletionService.completionCandidatesAt
  -> 旧 GdParserFacade.parseCompletionContext(快照源文本, byteOffset)
  -> kind / replaceableRange / receiverRange
  -> 在同代快照 AST 上定位已发布事实
  -> 枚举候选
```

源码：`FrontendSnapshotCompletionService.java:43-109,170-205,218-323`。

即使把第一条改为纯 Java，第二条也不会自动改变。查询时的新解析结果只可提供范围与上下文，不得把新 AST 节点身份写入旧快照 side
table；这条同代身份合同仍须保留。

### 3.2 恢复安全不仅是 parser 不抛异常

GDCC 的标准还包括：坏构造不能伪装成健康语义事实，健康兄弟继续发布，根因诊断只有一个 owner。

`FrontendSemanticAnalyzer.java:206-245` 的顺序为：

```text
skeleton 建立与发布
  -> FrontendErrorSubtreeAnnotator
  -> scope / variable / interface
  -> suite/body 解析与事实发布
```

`FrontendErrorSubtreeAnnotator.java:60-103` 当前识别四类 Error/Unknown 节点和空名 match binding，提升到最小恢复根；默认值有独立
island。它不根据任意 parse diagnostic 自动推断全部受损节点。

注意 skeleton 在标注器之前运行。损坏属性若以普通 `VariableDeclaration` 留下，单在后置标注器补 skipped 标记也未必能撤销其已注册
property/declaration origin。恢复信息必须在 skeleton 填充成员时可用，不能只在 body 阶段补救。

### 3.3 可以保留的基础

新入口复用原有 immutable AST record、`Node.getChildren()`、`Range`/`Point`、`AstMappingResult` 与 `AstDiagnostic`
。局部样例及坐标测试证明 UTF-8 字节偏移、0-based 字节列、CRLF/CJK/emoji 口径可兼容。

新 facade 每次创建 parser/tokenizer，可以共享 facade，无须加全局解析锁。快照冻结、contentVersion、乱序发布、声明溯源、只读
scope、reverse usages 等不因 parser 重写而需要整体重做。它们仍须以新 AST 做回归。

## 4. 遗留问题逐项判定

以下“改善”均指已经切换纯 Java 语义入口；只改依赖版本时旧路径行为不变。

| 问题                                  | 0.7.0 实测或源码事实                                                      | 判定与剩余工作                                            |
|-------------------------------------|--------------------------------------------------------------------|----------------------------------------------------|
| 部分链在函数尾、下一 `func` 前吞掉 receiver/下一函数 | `self.`、`v.normalized().` 前缀及下一函数均保留                               | 明显改善，需替换人为在点后加健康语句的旧夹具并补边界测试                       |
| `call().` 受限位置无可用语义事实               | `v.normalized()`、`make()` 前缀可定型；探针返回 Vector2 成员                    | 语义半边改善；旧补全分类器未改，不能承诺所有排版都已修复                       |
| 下一行关键字被并入悬空属性                       | 旧路径对 `v.normalized().` 后接 `pass` 可无诊断地变成 `.pass`；新路径正确留 `pass` 为兄弟 | 直接修复了该解析边界，但补全 replaceable 净化仍需保留                  |
| replaceableRange 指向下一语句 token       | `parseCompletionContext` 仍是旧实现                                     | 未解决；继续使用 `sanitizedReplaceableRange`，直到新上下文接口有覆盖测试 |
| `super.` 参数/成员补全                    | 新入口 `parseSuperExpression` 返回 null，receiver AST 消失                 | 实际回归；必须保留可识别的 super 前缀或发布等价上下文事实                   |
| 损坏 `if`/`for`/`match` 的健康兄弟         | 新入口丢弃坏构造，`var y` 等健康兄弟与局部变量仍保留                                     | 恢复结果可用；“坏节点必须在 skipped 表”需区分节点不存在与节点幸存             |
| `func f(x = 1 +)` 幻影参数              | 幻影 `+` 参数消失，`x` 保留但 `defaultValue == null`                         | 只解决旧形状问题；新的“坏默认值变必填”问题需处理                          |
| match `var :` 空名导致 inventory 崩溃     | 缺名 pattern 不再作为空名绑定存活；直接/嵌套样例保留健康兄弟                                | 崩溃来源改善；空 patterns/部分字典 pattern 仍应标明损坏，不能当完整规则      |
| 损坏属性 `var hp = (1` 或 `var hp = 1+`  | `hp` 仍注册；前者取恢复值 `1`，后者 value 为 null                                | 现行“不发布坏属性”合同回归                                     |
| 损坏 annotation argument 重复诊断         | 注解参数被截短，之后 sema 重新校验参数数量                                           | 单 owner 合同回归，已有测试和探针均复现                            |
| `return =3` / `return 1+`           | 变成普通裸 return；typed 函数再报 `sema.type_check`                          | 损坏 return 未 skip，发生级联诊断                            |
| 损坏数组/字典                             | 数组丢元素；字典插零宽空 sourceText 的 null literal                             | 无崩溃不代表完整语义，需恢复标记，避免把修复值当真实源码值                      |
| 只含 WARNING 的解析结果保留                  | key-only 字典 match 产生 WARNING，两路 range 相同                           | 有可验证样例，但丢 key 的 AST 表达力缺口仍未解决                      |
| `parse.internal` 整单元排除              | 新入口正常错误走 diagnostics；内部失败仍由 GDCC 捕获                                | 保留原合同及失败注入测试，不能用 strict parse 替代 tolerant          |
| 当前参数下标与 signature help              | 新入口未移植 Godot completion hooks；旧 DTO 仍只有三项                          | 未解决；不是单纯换 AST 查询就可覆盖所有未完成调用                        |
| 增量解析/语义分析                           | 新入口全量 parse                                                        | 未解决，也是当前计划明确排除项                                    |

## 5. 直接切换的主要风险

### 5.1 健康外观的损坏节点进入发布链路

这是最优先要解决的迁移阻断项，不应简单理解为“新 parser 不产 Error 节点所以所有恢复都不工作”。直接丢弃的坏构造通常安全；问题在幸存且失去损坏标记的构造。

| 输入                        | 新 AST/元数据后果                       | GDCC 实际影响                                      | 证据                                                           |
|---------------------------|-----------------------------------|------------------------------------------------|--------------------------------------------------------------|
| `var hp=1+`               | 普通 VariableDeclaration，value=null | property `hp` 仍注册，无 skipped 根                  | parser `GdScriptParser.java:1950-1971`；`probe07.log:100-112` |
| `var hp=(1`               | 恢复为字面量 1                          | 坏 property 存活，还可能追加类型提示                        | `probe07.log:86-98`                                          |
| `func f(x=1+): ...; f()`  | Parameter `x` 的默认值为 null          | 调用失败为 missing required parameter，失去“默认值正在编辑”信息 | parser `:2138-2152`；`probe07.log:70-84`                      |
| `@export_range(1, =3)`    | 注解仅剩一个实参                          | parser 错误之后再报 annotation 参数数量错误                | parser `:1797-1825`；既有 recovery 测试失败                         |
| `@export_range(0, 1+, 2)` | 中间坏实参被删除，只剩 0 和 2                 | 坏注解仍投影为 `export_range=0,2` 属性元数据               | `defect-examples-pure07-refreshed.log:200-218`               |
| `return 1+`，函数返回 int      | ReturnStatement.value=null        | 追加“int 函数不能裸 return”错误                         | `probe07.log:173-186`                                        |
| `{"k":}`                  | DictEntry.value 为零宽合成 null        | 本地声明仍进入 inventory，现有标注器不知道节点为恢复产物              | parser `:2779-2797`；`probe07.log:128-141`                    |
| `[1, ,3]`                 | 元素列表只剩 1 和 3                      | 损坏声明仍有 local，未 skip                            | parser `:2673-2694`；`probe07.log:143-156`                    |

这里没有声称所有恢复容器都已经发布了精确定型的 `expressionTypes`；探针中相关容器事实可能缺席，但局部变量和正常外观 AST
已经保留。风险是消费者无法区分真实值与 parser 修复值，不是本次已验证产生错误二进制。

参数恢复尤其不能只把失败参数从列表删除：这会改变参数个数、后续位置以及 body
中名称绑定。应保留可识别的参数名/类型，同时明确“默认值损坏”与“根本没有默认值”的区别；受损签名不能继续作为完全可靠的
callable boundary，也不能因此丢弃整个 callable body。

### 5.2 诊断 owner 发生变化与重复

新入口移植了重复参数、默认值顺序、rest 参数、循环控制、构造器 return 等部分检查。GDCC 现行语义文档中已有对应
owner，不能两边同时报同一根因。

实际验证：

- 循环外 `break`/`continue` 同时有 `parse.lowering` 和 `sema.loop_control_flow`，范围相同。证据：
  `owner-migration-pure07.log:2-18`。
- `func ping(a=1, b)` 的顺序错误由 parser 报告，parser 同时删掉 `b`；body 引用 `b` 又产生 binding 与 expression_resolution
  错误。证据：同日志 `:22-28`；parser `:2320-2322`。
- `value as Array[Array[int]]` 同时有 parser 的 nested collection error 和 sema 的 unsupported declared type
  error，诊断起点分别为 307 与 283，并非同一范围。证据：同日志 `:114-121`；parser `:1879-1889`。
- 空枚举从旧 parser-owned missing enumerator 改为 sema.class_skeleton-owned error；没有重复，属于归属变化，不能与上面实际重复等同。

推荐保留新 parser 的语法/上下文检查，但让 GDCC 基于结构化恢复/诊断归属关联到相应的损坏构造，对同一根因去重。对 nested
collection 应明确 parser 或 sema 中的一个 owner；采用 parser owner 时，sema 可保留 unsupported 状态而不再报同一限制。不能仅按起点或
range 相等去重：上述两个诊断分别锚在内部类型与外层 cast。GDCC analyzer 的检查仍需保留用于手工
AST、其他入口和自身语义合同。不要通过匹配英文消息前缀做 owner 识别。

### 5.3 部分链事实与诊断的区别

普通 `self.` 或 `make().` 只退回 receiver，仍可能被既有完成度查询使用。但没有 `MissingAttributeStep` 时，GDCC 还可能把前缀当作完整的
expression statement，产生 `sema.discarded_expression`。探针 `probe07.log:2-68` 已记录该额外诊断。

这意味着“能返回候选”不等于现行部分链合同已满足。需要恢复信息区分“receiver 前缀可定型”和“整个链尚未完成”，保留前者，同时抑制后者的完整表达式校验和根因重复。

`super.` 更严重：`parseSuperExpression` 在缺方法名时直接返回 null（parser `:2907-2938`），没有任何 receiver
可供成员查询。应对齐普通部分链的补全需求，且仍只枚举词法超类方法。

### 5.4 编译安全边界

当前编译任务解析阶段有 ERROR 就停止；`AnalysisRunner` 的 includeLowering 路径也不会把解析错误的共享快照送进
lowering。本文的已复现问题主要影响 inspection/LSP 事实和诊断质量。

不能把某些旧 `sema.compile_check` 断言不再出现直接称为“非法 super 被编译放行”：新 parser 提前拒绝该语法，编译仍被 parse
ERROR 阻断。需要迁移的是诊断 owner 与恢复质量，而非虚构不存在的编译安全漏洞。

上述门禁的前提是 parser 确实产生 ERROR。补充探针发现 `func f(x: ): pass`、`signal s(x: );` 等在新入口中没有诊断且 strict
成功，旧入口则报错；GDCC 把空 `TypeRef` 按正常未标注类型解析为 Variant。该静默路径同样存在于 vendor Godot parser，不能称为
Java 独有的移植错误，也不能据此断言完整 Godot 编译接受。详见补充报告 §2；本次未验证该输入的 lowering/backend。

## 6. Godot 对照与语义差异

### 6.1 恢复与补全并非同一套状态

Godot 的 `synchronize()` 同步集合、排除 `IF` 以及停在同步 token 前的政策已移植；新入口还增加 JVM 嵌套深度预算
256。它更可预测地控制换行/缩进和兄弟声明边界，这是主要收益。

Godot 编辑器参数提示同时依赖 parser 侧 `for_completion`、cursor token 标记、`make_completion_context`、completion call
stack 与 `current_argument`。这些钩子被 gdparser 明确裁剪，不存在于新入口中。

Godot 的普通编译/校验/LSP 文档分析通常在 parse 失败时不继续 sema；编辑器 completion 则会在恢复 AST
上尽力分析。这些不同入口的消费合同不能通过“移植 parser，继续使用 GDCC 原发布政策”自动等价复制。

Godot 字典也插入 dummy recovery literal；gdparser 的 `nullPlaceholder()` 是相应适配，并非它独自发明的新恢复策略。但 GDCC
side table 当前没有该节点的 recovery 身份，仍需要消费侧适配。

### 6.2 运算符变化与已接受差异

以下 AST 来自探针或对应解析规则；数值是按固定版本源码/AST 推导的结果，不是 Godot 或 GDCC 二进制求值结果。

| 表达式          | 旧 tree-sitter AST | 0.7.0 纯 Java AST | Godot 固定版本源码对应行为                          |
|--------------|-------------------|------------------|-------------------------------------------|
| `2*3**2`     | `(2*3)**2`        | `2*(3**2)`       | 新入口对齐                                     |
| `1+2 as int` | `1+(2 as int)`    | `(1+2) as int`   | 新入口对齐                                     |
| `-x**2`      | `(-x)**2`         | `-(x**2)`        | 新入口对齐                                     |
| `-2**2`      | `(-2)**2`         | `-(2**2)`        | tokenizer 吸收紧邻数字的负号，parser 结构为 `(-2)**2`  |
| `2**3**2`    | 左结合               | 左结合              | `precedence + 1` 规则为左结合，推导结果 64；本次未对此样例求值 |

**取消数字符号吸收已经接受：`-2**2 -> -(2**2)` 是预期行为，不是待修复缺陷，也不是迁移阻断项。** 固定 Godot 版本源码对应结构推导为
4，新入口探针 AST 对应 -4；这里的数值是结构推导，本次没有
Godot 进程求值。新入口为保持 UnaryExpression 形状而取消符号吸收（文档 D1），再叠加 Godot 优先级，产生这个已知差异。源码还表明
`- 2**2` 因空格不吸收符号，按结构应为 -4；带空格形式本次未单独做 AST/运行探针。

gdparser 文档 §5.5 说明了 D1，§5.8 表格中负数字面量一行应按“不吸收符号”的条件理解，不能据此声称与真实 Godot tokenizer
完全一致。迁移时应按已接受策略保留行为测试，并将其与仍需修复的恢复/发布问题分开。

相关现行 GDCC 文档/测试包括 `frontend_cast_expression_implementation.md`、
`frontend_unary_binary_expr_semantic_implementation.md` 和 `FrontendCastParseBehaviorTest`，实施迁移时需同步。

### 6.3 其他合法性和 AST 差异

- 新入口拒绝普通表达式 `return [1, 2, ..]` / `return {"x": 1, ..}`。既有两个 container parse 测试虽命名为 pattern，夹具实际在
  return expression 中；真实 match `[1, ..]`、`{"a": 1, ..}` 正向探针仍无诊断且 openEnded=true。不能据这两个失败声称 match
  开放模式回归。
- 新入口接受一些 Godot 合法而旧路径有问题的 `$%Child`、嵌套类体 `extends`、文档字符串、match pass 等；这些形态需进入 GDCC
  骨架/语义回归，parser 接受不代表后端支持面自动扩大。
- 表达式内部注释不再作为 UnknownExpression 污染 AST，属改善；dedent 尾窗口混合缩进注释组归属改变，不能承诺全部 range/归属
  parity。
- key-only 字典 match 仍丢 key 并产生 WARNING，是共享 AST 表达力缺口，不是重写已经解决的事项。
- 新入口的 Godot 基线是 master，而 GDCC runtime 是 4.5.1。关键恢复/运算符机制本次同时核对了两版，但新增语法和检查仍需单独版本审计。没有建议直接重置
  vendor；应先确定前端语言目标和 runtime 支持策略。
- > 256 层嵌套加跨行 continuation 的作用域恢复仍有已登记限制；现有护栏只保证终止、诊断有效，不保证全部兄弟归属正确。参见
  gdparser 文档 `:224-233`。

## 7. 对 signature help / call hint 的影响

上次调研的缺口没有被版本升级自动消除，且新入口对未完成调用的 AST 并不统一：

| 光标所在输入     | 新入口                                  | 旧 completion 分类的实测结果 |
|------------|--------------------------------------|----------------------|
| `f(`，EOF   | CallExpression 不存在；var 外壳 value=null | CALL_ARGUMENT，无候选    |
| `f(1,`，EOF | CallExpression 不存在                   | CALL_ARGUMENT，无候选    |
| `f(1`，EOF  | CallExpression 存活，args=[1]，但缺右括号     | UNKNOWN              |

证据：`probe07.log:263-306`。因此仅依赖 AST 的 argument range 或已选中的 `FrontendResolvedCall`，无法可靠覆盖最常见的刚输入
`(`、刚输入逗号情形。

完整实现至少需要：

1. parser/tokenizer 驱动的 cursor context，能定位所属最内层调用/被调前缀并发布 0-based 参数位置。
2. 未完成调用信息，即使语义 CallExpression 被丢弃也可提供范围级 callee 和实参位置，不伪造已成功解析的调用事实。
3. GDCC 查询层的 callable 候选查询。已选中完整调用可复用 `FrontendResolvedCall`；未选中/实参未完成时，需按词法作用域、receiver
   和调用种类查候选，不能要求 overload 已被实参选中。
4. 签名 DTO 和只读查询，区分候选 signatures、activeParameter、可选 activeSignature、参数名/类型/default/vararg 与部分可信度。

`ExactCallableBoundary` 只有固定形参类型与 vararg，不含名字/default；`argumentTypes()` 是实参快照。精确 callable 的
declarationSite 可复用，但不能把所有调用都假定为 FunctionDef；dynamic、lambda、signal、constructor 等需要按 route 分开处理。

推荐参照 Godot 编辑器的 parser 钩子，而非其 LSP `get_left_function_call` 的逐字符扫描。后者只计 `()` 和逗号，不正确处理字符串、注释、
`[]`/`{}` 等，是可参考的接口流程而非可靠的参数定位算法。

`GdScriptParserFacade.tokenize()` 是 raw token stream，没有 parser 的 multiline 干预。可以用于词法范围辅助，但不能把其
ERROR 数量作为源码合法性标准，也不能不处理布局状态就把它当完整编辑器调用上下文。

当前 GDCC 内部坐标仍为 UTF-8 字节；未来接 LSP 默认 UTF-16 时应在适配层换算，不能照搬 Godot 的字符列/tab 展开口径。

## 8. 所需修改及优先级

### 8.1 P0：先让 parser 发布足够恢复信息

必须解决的问题：损坏声明/表达式、未完成成员链、损坏默认值、损坏 annotation 和部分容器在 AST 中仍可被识别；信息必须与本次 AST
同代。

有两条可行方向，实施前应确认其一：

| 方向                                 | 上游修改                                                                              | GDCC 修改                                                  | 权衡                                 |
|------------------------------------|-----------------------------------------------------------------------------------|----------------------------------------------------------|------------------------------------|
| 复用既有错误/缺失节点                        | 让新入口在幸存坏表达式处构造 ErrorExpression，悬空点保留 MissingAttributeStep；必要时坏声明构造 ErrorStatement | 多数 scope/body skipped 消费点可保留；skeleton 和受损 signature 仍需加固 | 最贴近现有 GDCC 合同，但需改变新入口“不产这些节点”的既定政策 |
| 发布 parser-neutral 的结构化 recovery 信息 | 新结果携带同代恢复节点/根及性质，区分损坏默认值、缺失 token、可分析前缀、合成值                                       | 把恢复信息接到 skeleton 前置判断和 skipped/诊断 gate；查询层按性质处理          | 保留 Godot 式部分 AST，但增加结果模型与消费适配      |

推荐优先评估复用现有节点的最小方案；若上游坚持当前 AST 政策，采用结构化 recovery 信息，而不是让每个 analyzer 从 sourceText
和诊断文案重新猜损坏状态。

无论选择哪条，默认值岛被 skip 后都不能仅以 `defaultValueFunc == null` 继续推导“本参数必填”。需要同时表达受损签名的不完整性，保留
body 的参数绑定，不把部分签名当完整 exact boundary。

需要关注的上游位置：`GdScriptParser.java` 的
variable/parameter/signature/annotation/return/array/dictionary/grouping/call/super/suffix 分支。现有 `AstMappingResult`、
`AstDiagnostic`、Error/Missing 节点所在共享包若演进，要明确公开 API 变更，不凭空添加一套长期双版本兼容逻辑。

**不推荐全局按 diagnostic range 相交就跳整条语句。** function/class range 包含健康 body；缺失 token 诊断可能为零宽或指向
previous/下一 token；这样会把可分析函数体或健康兄弟一起跳掉。枚举现有 containment guard 可保留，但不能推广为全部恢复的唯一真源。

### 8.2 P0：对齐诊断 owner 和骨架发布

| GDCC 文件/区域                                                          | 必要改动                                                                                    |
|---------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `frontend/parse/GdScriptParserService.java`                         | 使用新 facade 的 tolerant parse；保留 DiagnosticManager、parseFailed、parse.internal；更新失败注入 seam |
| `FrontendClassSkeletonBuilder.java`                                 | 填成员/参数/annotation 前消费结构化恢复状态，避免坏 property、截短签名提前注册                                      |
| `FrontendErrorSubtreeAnnotator.java`                                | 兼容选定的新恢复政策；不存在的坏节点无需强造 skipped；幸存坏节点须有明确恢复根                                             |
| `FrontendParameterDefaultMetadataOwner.java` 与 callable resolver    | 保留默认值岛边界，区分无 default 与损坏 default，不删除参数、不产生虚假缺参错误                                        |
| `FrontendAnnotationUsageAnalyzer` / export annotation support       | 坏 annotation 只跳其自身投影，保留健康宿主，不再报派生参数校验错误                                                 |
| `FrontendLoopControlFlowAnalyzer`、type-check、binding/expression 支持层 | 对新增 parser-owned 根因去重，保留 analyzer 自己的独立入口合同                                             |
| `FrontendSuiteResolver` / scope / variable inventory                | 继续只发布健康子树，检查空 match section/partial pattern 与合成容器值                                      |

`parse.lowering` 是现有对外 category，且 RPC/inspection 消费它。最低风险是保留 category 并把文档解释改为 parser
的普通语法诊断，不为移除 CST mapper 而顺带重命名协议字段。新诊断结构若支持精确 owner，优先使用结构而非字符串。

### 8.3 P1：补全迁移

短期混合路径可以复用 `FrontendSnapshotCompletionService` 的候选枚举、遮蔽、全局命名空间、坐标范围净化和同代事实匹配，但必须新增
`super.` 和真实函数尾部输入测试，并明确仍有旧上下文分类器局限及 native 依赖。

完整方案应在 gdparser 新入口提供光标上下文，并切换服务中的旧 `CompletionContext`/`COMPLETION_FACADE` 依赖。新的上下文只提供
DTO/range，或作为同代解析结果的一部分；不得把另一次解析的 Node 身份带进快照事实表。

恢复/查询契约至少覆盖 `self.`、`v.`、`make().`、`v.normalized().`、`super.`、多层链、括号/cast
receiver、成员中间光标、下一语句/下一函数、无最终换行以及 CJK/emoji/CRLF。

只切换语义 parser 并不会使 GDCC 完全无 native 依赖：补全静态 facade 仍加载旧语言库。是否移除 tree-sitter
依赖和资源属于后续配置/打包工作，需单独授权与验收。

### 8.4 P1：语言行为与文档/测试迁移

- 按已接受的“不吸收数字符号”策略更新相关行为测试；Godot 语言版本目标另行明确，不再把 `-2**2` 列为待决策或待修复问题。
- 保留真实 match 开放模式的正向覆盖；把普通 expression 中 `..` 的旧接受行为改为经 Godot 核对的负向测试，而非机械保留旧
  AST。
- 空枚举、参数顺序、rest、非法 super、nested collection 等按最终 owner 更新测试，但保留“不重复、健康兄弟继续、无伪事实”断言。
- 将真实源码 parser 行为测试与手工 AST 的 skipped 表测试分开；不因 parser 不再产 ErrorStatement 而删除恢复安全验收。
- 同步 LSP 计划 §3/Phase 1/Phase 2/Phase 5 的入口与限制、frontend_rules、diagnostic_manager、scope/RPC
  文档及受影响特性文档；本次没有改写这些事实源。
- 代码注释使用 `///`，解释恢复不变量和原因，不引用规划章节/阶段编号；record 的组件说明放类级 `@param`
  。迁移触及的旧注释中已有规划依赖，应随相应代码改动清理。

依赖版本修改最终需要触及 `build.gradle.kts`，根据 AGENTS.md 需先取得明确配置修改许可。本次没有修改它。

## 9. 测试证据

### 9.1 GDCC 原基线

```bash
script/run-gradle-targeted-tests.sh --tests "CompletionServiceTest,FrontendSnapshotQueryServiceTest,SnapshotQueryEdgeTest,DocumentationAtTest,AnalysisRunnerRecoveryTest,FrontendErrorRecoveryIntegrationTest,GdScriptParserServiceDiagnosticManagerTest,FrontendCastParseBehaviorTest,FrontendEnumParseBehaviorTest,FrontendContainerLiteralParseBehaviorTest,FrontendMatchParseBehaviorTest,FrontendConditionalParseBehaviorTest,FrontendAnnotationParseBehaviorTest"
```

13 类、143 tests 全通过，0 failed/error/skipped，约 22 秒。依赖仍为 0.6.0。wrapper 使用 `--no-daemon --info --console=plain`
。此结果不是正式 0.7.0 验收。

### 9.2 新解析器自身

| 测试类                             | 实际用例数 | 通过  | 失败 |
|---------------------------------|-------|-----|----|
| GdScriptParserErrorRecoveryTest | 47    | 47  | 0  |
| GdScriptParserFacadeTest        | 11    | 11  | 0  |
| SourceTextIndexTest             | 12    | 12  | 0  |
| GdTokenizerTest                 | 64    | 64  | 0  |
| GdScriptParserTest              | 224   | 224 | 0  |
| GdParserParityTest              | 35    | 35  | 0  |
| 合计                              | 393   | 393 | 0  |

Tokenizer/Parity 数量包含各自 13 个动态 fixture 测试，不是仅计 `@Test` 方法。

### 9.3 GDCC 隔离迁移实验

| 测试类                                         | 用例  | 通过  | 失败 |
|---------------------------------------------|-----|-----|----|
| CompletionServiceTest                       | 29  | 25  | 4  |
| FrontendSnapshotQueryServiceTest            | 17  | 16  | 1  |
| DocumentationAtTest                         | 6   | 6   | 0  |
| AnalysisRunnerRecoveryTest                  | 7   | 7   | 0  |
| FrontendErrorRecoveryIntegrationTest        | 14  | 4   | 10 |
| FrontendCastParseBehaviorTest               | 6   | 4   | 2  |
| FrontendEnumParseBehaviorTest               | 3   | 2   | 1  |
| FrontendContainerLiteralParseBehaviorTest   | 16  | 13  | 3  |
| FrontendMatchParseBehaviorTest              | 12  | 12  | 0  |
| FrontendConditionalParseBehaviorTest        | 14  | 14  | 0  |
| FrontendAnnotationParseBehaviorTest         | 5   | 5   | 0  |
| FrontendParameterDefaultMetadataOwnerTest   | 26  | 24  | 2  |
| FrontendAnnotationUsageAnalyzerTest         | 26  | 26  | 0  |
| FrontendLoopControlFlowAnalyzerTest         | 10  | 5   | 5  |
| FrontendSuperCallSemanticsTest              | 19  | 14  | 5  |
| FrontendClassSkeletonTest                   | 31  | 31  | 0  |
| FrontendEnumSkeletonTest                    | 23  | 21  | 2  |
| FrontendEnumScopeTest                       | 14  | 14  | 0  |
| FrontendScopeAnalyzerTest                   | 18  | 18  | 0  |
| ModuleAnalysisSnapshotImmutabilityTest      | 4   | 4   | 0  |
| FrontendExpressionSemanticSupportTest       | 60  | 59  | 1  |
| FrontendMatchSemanticsTest                  | 12  | 12  | 0  |
| FrontendContainerLiteralSemanticSupportTest | 22  | 22  | 0  |
| 合计                                          | 394 | 358 | 36 |

首批 11 类的 129 个用例在去掉 shadow 的同一环境全部通过；第二批有失败的 5 类也逐类重跑 0.6.0，全部通过。没有通过修改原测试掩盖失败。

补充跑 `GdScriptParserServiceDiagnosticManagerTest` 为 3/5 通过，两个失败涉及默认纯解析路径的诊断/节点形状变化；旧两参注入
seam 的内部失败测试仍通过。日志为 `logs/supplemental-GdScriptParserServiceDiagnosticManagerTest.log:23-51`，同环境基线为
5/5，单独验证注入路径的日志为 `logs/supplemental-legacy-seam-method.log`。该补充类没有重复计入上表。

不能把 36 个失败都称为新 parser bug：其中包括旧形状断言、有意 Godot 合法性修正、检查归属变化，也包括已复现的 super
补全回归、重复诊断及坏事实发布。分类以本文各节的行为证据为准。

### 9.4 研究产物与复现

- `tmp/probes/gdparser07-research/`：纯 Java parser 编译产物、原测试编译产物、JUnit Launcher、对比/数值/坐标探针及
  `junit-*.log`、`probe.log`、`numerical.log`。
- `tmp/probes/gdparser07-integration/`：隔离 shadow、真实 GDCC 管线探针、`env.sh` 及 `logs/focused-pure07.log`、
  `logs/probe07.log`、`logs/owner-migration-pure07.log`、逐类日志。

编译只输出到 `tmp`，输入读取同级 gdparser 源码和缓存依赖；两个仓库无生产修改。已经编译后可运行以下研究命令：

```bash
source tmp/probes/gdparser07-integration/env.sh
java --enable-native-access=ALL-UNNAMED $JVM_NATIVE_FLAGS \
  -cp "$RES/test-classes:$CP" RunJUnit \
  gd.script.gdcc.api.analysis.CompletionServiceTest
java --enable-native-access=ALL-UNNAMED $JVM_NATIVE_FLAGS \
  -cp "$RES/test-classes:$CP" gd.script.gdcc.api.analysis.Pure07IntegrationProbe
java --enable-native-access=ALL-UNNAMED $JVM_NATIVE_FLAGS \
  -cp "$RES/test-classes:$CP" gd.script.gdcc.api.analysis.Pure07OwnerMigrationProbe
```

这些是 research-only classpath 实验，不是正式 Gradle 迁移实现。后续正式修改仍必须用仓库 targeted wrapper 做验收，并验证
actual 0.7.x 工件。

## 10. 建议实施顺序与验收门

1. **冻结剩余迁移语义决策。** 确认错误节点/结构化 recovery 方向、损坏默认值与签名发布策略、Godot 语言版本；数字符号不吸收策略已接受，无须重新决策。
2. **先补上游恢复和光标信息的最小缺口。** 保留健康前缀及兄弟，不以 null 冒充无 default/无 initializer；特别覆盖 super
   和刚输入调用括号/逗号。
3. **再改 GDCC 默认解析入口与消费者。** 骨架之前可识别坏成员，默认值岛仍独立，新的 parser-owned 诊断与 sema 不重复，保留
   parse.internal 和 compile gating。
4. **迁移成员补全后再加参数提示。** 可短期混合，但最终 cursor context 不依赖旧 tree-sitter 才能移除其遗留分类问题；signature
   help 单独建立候选查询与 DTO。
5. **以行为而非旧偶然 AST 形状验收。** 健康同函数/同文件/跨文件继续，损坏节点无伪事实，参数不被重编号，annotation 单
   owner，super 方法集合正确，陈旧快照同代自洽。
6. **完成实际工件、定向 frontend/lowering 与打包回归后同步事实源。** 无解析 ERROR 的常规源码需证明 binding/type/LIR
   行为未漂移；有 ERROR 的输入不得进入 lowering；不得先把 Phase 6 状态改为“已实施”。

本次未进行正式依赖升级、全量构建、Godot 二进制运行或完整后端 runtime suite。报告中的 Godot 结果来自固定版本源码追踪，新旧
Java AST 结果来自实际探针；两者证据口径明确分开。

## 11. 关键源码索引

GDCC 路径前缀：`src/main/java/gd/script/gdcc/`。

| 路径/位置                                                                        | 用途                                 |
|------------------------------------------------------------------------------|------------------------------------|
| `frontend/parse/GdScriptParserService.java:18-70`                            | 当前旧入口、诊断与 internal recovery        |
| `frontend/sema/analyzer/FrontendSemanticAnalyzer.java:206-245`               | skeleton/annotator/scope 发布顺序      |
| `frontend/sema/analyzer/FrontendErrorSubtreeAnnotator.java:60-103`           | 现行错误/默认值恢复根                        |
| `frontend/sema/FrontendClassSkeletonBuilder.java:487-508`                    | enum parser diagnostic guard/空成员防御 |
| `api/analysis/FrontendSnapshotCompletionService.java:72-109,170-205,263-323` | 补全旧 parser、范围净化、同代 receiver 匹配     |
| `frontend/sema/FrontendResolvedCall.java:18-38,323-352`                      | 实参快照与精确形参边界区别                      |
| `api/analysis/AstUnitIndex.java:102-178,209-254`                             | 半开节点范围、行列/字节换算                     |

gdparser 路径前缀：同级仓库 `src/main/java/dev/superice/gdparser/`。

| 路径/位置                                               | 用途                                  |
|-----------------------------------------------------|-------------------------------------|
| `frontend/gdscript/GdScriptParserFacade.java:12-63` | 无状态新入口、tolerant/strict/raw tokenize |
| `frontend/gdscript/GdScriptParser.java:491-577`     | synchronize/深度预算                    |
| 同文件 `:1797-1825,1831-1897`                          | annotation/type 恢复与诊断               |
| 同文件 `:1916-1971,2138-2152,2290-2358`                | variable/default/signature 部分节点     |
| 同文件 `:2673-2797`                                    | array/dictionary 与 recovery null    |
| 同文件 `:2907-2938,3121-3246`                          | super、悬空链、调用参数失败                    |
| `infra/treesitter/CompletionContext.java:16-38`     | 旧上下文仅 kind/replaceable/receiver     |

Godot 固定版本链接：

- [4.5.1 synchronize](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/gdscript_parser.cpp#L525)。
- [4.5.1 parse_attribute / parse_call](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/gdscript_parser.cpp#L3286)。
- [4.5.1 dictionary recovery](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/gdscript_parser.cpp#L3252)。
- [4.5.1 tokenizer 负号吸收](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/gdscript_tokenizer.cpp#L1519-L1531)
  ，关键判定在 L1523。
- [4.5.1 precedence](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/gdscript_parser.h#L1407)。
- [4.5.1 编辑器 completion](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/gdscript_editor.cpp#L3446)。
- [4.5.1 LSP 调用字符扫描](https://github.com/godotengine/godot/blob/4.5.1-stable/modules/gdscript/language_server/gdscript_extend_parser.cpp#L790)。
- [gdparser 对照的 Godot 固定 commit](https://github.com/godotengine/godot/tree/c016f22009aa773f0b2e6fce72baec168746491f/modules/gdscript)。
