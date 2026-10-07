# 纯 Java GDScript Parser 实施计划（Godot 官方 Parser 移植版）

本文档规划将解析后端从 tree-sitter-gdscript 迁移到纯 Java 重写的 Godot 官方 parser 移植版。首版目标是**双 parser 并存**：tree-sitter 路径完全保留，新 parser 产出与之相同的解析器无关 AST。

## 1. 背景与目标

### 1.1 动机

- tree-sitter-gdscript 基于 GLR，未实现 Godot「换行即语句结束」的 tokenizer 级语义（Godot 的 `NEWLINE`/`INDENT`/`DEDENT` 由 tokenizer 显式产生，附带 pending 机制与 multiline 抑制规则），在换行/缩进边界场景与官方行为不一致。
- tree-sitter 的 `ERROR` 节点是通用容错产物，恢复粒度不可控，难以为 LSP 提供可预测的诊断与部分 AST；Godot 官方 parser 有明确的 panic-mode + 同步集恢复机制。
- 现有 AST 抽象（`frontend.ast`）已是解析器无关设计，具备承载第二 parser 的条件。

### 1.2 目标

- 新 parser（包 `frontend.gdscript`）：纯 Java、无 native 依赖，忠实移植 Godot 官方 tokenizer 与 parser 的**词法/语法/错误恢复行为**，直接产出 `frontend.ast` 的 AST。
- tree-sitter 路径（`infra.treesitter` + `frontend.cst` + `frontend.lowering`）：首版**不做任何行为变更**，继续作为对照与回退。
- 移植范围严格限定为 parser：**裁剪语义分析**（类型解析、常量折叠、符号表、补全钩子、注解验证等），只产出干净 AST 与语法诊断。

### 1.3 Godot 参考基线

- 仓库：`godotengine/godot`，commit `c016f22009aa773f0b2e6fce72baec168746491f`（master，调研时点，见 `vendor/godot/COMMIT_SHA`）。
- 参考文件已 vendor 进仓库，实施时直接对照：
  - `vendor/godot/gdscript_tokenizer.h` / `vendor/godot/gdscript_tokenizer.cpp`
  - `vendor/godot/gdscript_parser.h` / `vendor/godot/gdscript_parser.cpp`
- 移植代码与 vendor 源文件的对应关系以函数级 `///` 注释标注（§3.3）；升级 Godot 基线时须同步更新 vendor 副本与 `COMMIT_SHA`，并走独立的评估流程（参考 tree-sitter 计划的 grammar 锁定条款）。
- 许可：Godot 为 MIT License；vendor 文件仅作对照参考，不参与编译。若未来直接复制其代码片段进发布产物，须附带 Godot 版权与许可声明。

## 2. 现状调研结论

### 2.1 现有代码库

- `frontend.ast` 解析器无关：sealed `Node`/`Statement`/`Expression` + record 节点；`Range` 为 UTF-8 字节偏移 + 0-based `Point(row, column)`（列为字节列）；集合组件构造时 `List.copyOf` 冻结。
- AST 层仅两处与 `frontend.cst` 耦合：
  - `AstFactory` 的 helper 签名使用 `CstRange`/`CstNodeView`/`CstIssueKind`——新 parser **不使用** `AstFactory`，直接调用 record 构造器。
  - `ErrorStatement`/`ErrorExpression` 的 `kind` 组件类型为 `CstIssueKind`（`ERROR`/`MISSING`）——语义通用，v1 直接复用，不做包迁移（避免破坏现有 mapper 与序列化）。
- `CstToAstMapper` 深度耦合 tree-sitter grammar 的节点类型名与字段名（如 `function_definition`、`childByField("body")`），新 parser **不复用**该 mapper，直接构建 AST。
- 项目中不存在 parser 接口抽象；`GdParserFacade` 是 tree-sitter 的唯一入口。新 parser 使用独立 Facade，两者不共享入口类。
- `LiteralExpression.kind` 当前取 tree-sitter 节点类型名（见 §5.3），该字符串集合须视为 AST 契约词汇表。
- 属性 get/set 块（`var x: int: get = a, set = b` 与内联 `get:`/`set(value):` 套件）：现有 AST **丢弃**该信息（`VariableDeclaration` 仅含 name/type/value/static）。parity 规则：新 parser 必须正确消费该语法结构，但同样不产出对应 AST 节点。
- 注释：tree-sitter 路径将 `comment` 节点映射为 `CommentStatement`，`region_start`/`region_end` 映射为 `RegionDirectiveStatement`；Godot tokenizer 不将注释放入 token 流。新 parser 须将注释作为 trivia 保留并分类（见 §5.4）。
- 诊断模型 `AstMappingResult(SourceFile, List<AstDiagnostic>)` 与 `AstDiagnostic(severity, message, nodeType, range)` 解析器无关，直接复用为新 parser 的返回类型。
- 序列化（`frontend.serialize`）按 record 组件反射工作，对新 parser 产出的 AST 天然可用，是 parity 对比的可靠工具。
- fixture 基线：13 个 fixtures 在当前路径全部零诊断、零 `Unknown*` 节点（`CstToAstMapperTest` 动态断言）；§5.2/§5.4 的关键形状已在本计划调研期用现有路径 dump 实证并写入契约。

### 2.2 Godot 官方实现机制（对齐目标）

Tokenizer（`gdscript_tokenizer.cpp`）：

- 增量 `scan()`：每次调用返回一个 token；错误进入 error 队列（消息存于 token 的 `literal` 载体），以 `ERROR` token 优先返回，扫描可继续。
- 关键字为独立 token 类型；`true`/`false`/`null` 扫描为 `LITERAL`；`PI`/`TAU`/`INF`/`NAN` 为独立常量 token。
- 缩进：indent 栈 + `pending_indents` **有符号计数器**（非队列）：增缩进 `++`、减缩进按弹出层数 `--`，`scan()` 每次调用只发一个 `INDENT`/`DEDENT`；不对齐的 dedent 报错后把新宽度压回栈但**不** `++`；tab 按宽度 4 计；行首空格/tab 混用报错但继续；空行与纯注释行不影响缩进层级、不发射 `NEWLINE`；EOF 合成最终 `NEWLINE` 并按层数扣减 `pending_indents`。
- 换行：pending newline 模型——代码后的物理换行调度一个 `NEWLINE`；`multiline_mode`（由 parser 在分组构造中 push/pop）下丢弃 pending newline；**无括号嵌套计数**；反斜杠续行消耗换行且不发射。
- 符号吸收：`+`/`-` 后紧跟数字（不含 `.5` 形态）且前一 token 不在「可作为二元运算符左操作数」集合（`IDENTIFIER`/`LITERAL`/`SELF`/三种右括号/`PI`/`TAU`/`INF`/`NAN`，即 `can_precede_bin_op()` 为假）时，符号被吸收进数字字面量（见决策 D1）。
- EOF 坐标：`_advance()` 在消费最后一个字符时立即执行 `newline(true)`（行号 +1、列归 1），此后产生的合成 token（`DEDENT`/EOF）以及 EOF 前最后一个真实 token 的 end 行列落在**不存在的物理行**上——`Range` 必须以物理字节游标为准换算（见 §5.1）。
- 注释：`#` 注释不进入 token 流（tools 构建另存 comment 表但不进 AST）；本项目按 D2 以 trivia 形式保留（§5.4）。
- 字面量：十/十六/二进制数字（下划线、指数、前导 `.` 浮点）；单/双/三引号字符串，`r`/`&`（StringName）/`^`（NodePath）前缀（仅引号形态，`&name`/`^path` 裸标识形态在本基线中为解析错误），转义与 `\u`/`\U`；`@` 注解 token。
- `:=` 无独立 token（`COLON` + `EQUAL`）；`->`、`..`、`...`、`**`、`<<`/`>>`、`&&`/`||` 与 `and`/`or` 各有对应 token。

Parser（`gdscript_parser.cpp`）：

- 递归下降 + Pratt 表达式解析：`parse_precedence()` 循环 + `get_rule()` 规则表 + `PREC_*` 优先级枚举；二元运算符（含 `**`）左结合（右操作数以 precedence+1 解析）；赋值仅在允许的上下文解析，其 RHS 以禁止赋值的上下文解析（`a = b = 1` 报 `Assignment is not allowed inside an expression.`）；无 walrus 表达式。
- `parse_program`：脚本级前缀（注解、独立文档字符串、`class_name`、`extends`，允许同行 `class_name X extends Y`）后进入 `parse_class_body`。
- `parse_class_body`：成员分派（`var`/`const`/`func`/`signal`/`class`/`enum`/`static` 前缀/类级注解/`pass`/文档字符串）；Godot 3.x 移除关键字（`export`/`tool`/`onready`/`setget`/`yield`/旧 RPC 关键字）报带迁移提示的错误——与现有 mapper 的 3.x 拒绝策略方向一致。
- `parse_suite`：冒号后若有 `NEWLINE` 则要求 `INDENT`、解析语句直到 `DEDENT`；否则解析单行套件（分号可串多个语句）；返回空的失败语句被跳过，**仅统计当前 suite 内 `parse_statement` 返回空的次数**——`error_count++ > 100`（即第 102 次）时发 `Too many statement errors.` 并结束**当前 suite**（不终止整个 parse；`push_error` 本身不计数）。
- 语句结束：`end_statement()` 循环消费连续的 `NEWLINE`/`SEMICOLON` 直到非结束符（EOF 停住不消费）；`lambda_ended`/`in_lambda` 状态也算结束。
- 错误恢复：`panic_mode` 标志 + `synchronize()`——重置 panic 后前进至换行/分号/EOF 或疑似语句/成员起始 token（`class`/`func`/`static`/`var`/`const`/`signal`/`for`/`while`/`match`/`return`/注解；`if` 故意排除，因其可出现在表达式中），停在同步 token 之前不消费；接入点为 `parse_program` 前缀、`parse_class_body` 循环、`parse_statement` 末尾三处；`push_error` 累积错误列表；失败构造按构造返回 `nullptr`/部分节点/原操作数；recovery suite 占位。
- 表达式形态：`elif` 表示为 false suite 内嵌套 `IfNode`；`.`/`[`/`(` 后缀链由 Pratt 循环逐个构造 `SubscriptNode`/`CallNode`；`as`/`is`/`await`/三元 `v if c else e`/`$`与`%` 节点路径/`preload` 各有专门入口；`a not in b` 由 `NOT` 的 infix 规则（`PREC_CONTENT_TEST`）解析；`PI`/`TAU`/`INF`/`NAN` 经 `parse_builtin_constant` 折成数值 `LiteralNode`。
- lambda：multiline 上下文中进入 lambda 体前调用 tokenizer 的 `push_expression_indented_block()`（缩进状态快照），体结束后用原始 `scan()` 跳过多余的 `DEDENT`/`INDENT`/`NEWLINE`（不改变 `previous`）再 `pop`。
- 类型解析：`parse_type()` **不自行 push/pop multiline，继承调用上下文**——成员/局部变量类型与函数返回类型（在签名 `pop_multiline()` 之后解析）不能因类型括号跨行；函数/信号参数类型处于签名 multiline 作用域内，允许跨行。验收须配对覆盖两类上下文（参数类型跨行合法、成员变量类型括号不自行续行）。
- 类体 `pass`：Godot 不产出任何节点（仅消费 token）；本项目 AST 保留 `PassStatement`（偏差 D4，§5.2）。

须裁剪的语义耦合（不移植）：

- `GDScriptAnalyzer` 交互、`DataType` 解析、类型约束与 `resolved_type`、`reduced`/`reduced_value` 常量折叠。
- `SuiteNode` 局部符号表、标识符 source 解析、成员索引重复检查（保留纯语法层面可判定的诊断即可，见 §5.6）。
- 代码补全状态机与 `make_completion_context` 钩子、编辑器 cursor 跟踪。v1 新 parser 不提供补全能力；补全继续由 tree-sitter 路径的 `CompletionContextAnalyzer` 承担，后续再评估新路径的补全方案。
- 注解名称/目标/参数个数验证与 `@tool`/`@icon` 等 parser-time 动作（仅解析注解语法）。
- 警告系统、工具构建的文档注释提取、`GDScriptParserRef`/`GDScriptCache` 依赖解析、二进制 tokenizer 路径。

## 3. 总体架构

### 3.1 包结构

新增包 `dev.superice.gdparser.frontend.gdscript`（在 `module-info.java` 中增加 `exports`），主要类型：

- `GdTokenType`：token 类型枚举，逐一对齐 Godot `Token::Type`（含 `VCS_CONFLICT_MARKER`、`BACKTICK`、`QUESTION_MARK` 等错误提示改进 token）。
- `GdToken`：record，携带类型、源文本切片、字面量种类、ERROR 消息（对齐 Godot `ERROR` token 的 `literal` 载体）、起止字节偏移与 1-based 行/列（内部对齐 Godot 坐标，便于错误消息对齐；对外经 `SourceTextIndex` 换算为 `Range`）。
- `GdTokenizer`：`GDScriptTokenizerText` 的移植，增量 `scan()`；附注释 trivia 收集（偏差 D2，见 §5.4）；提供缩进状态快照 push/pop（对齐 `push_expression_indented_block`，供 lambda 使用）。
- `SourceTextIndex`（包内）：UTF-8 字节偏移 ↔ 行列换算、行起始表；以**物理字节游标**为 `Range` 的唯一事实源（0-based 行、字节列）；Godot 合成 token（EOF 处 `NEWLINE`/`DEDENT`）的行列不参与 range 换算，锚定物理 EOF（§2.2 EOF 坐标条款）。
- `GdScriptParser`：递归下降 + Pratt，直接构建 `frontend.ast` 记录；内部持有诊断列表、panic 标志、multiline 栈、lambda 状态。
- `GdScriptParserFacade`：公开入口，`parse(String) -> AstMappingResult`（容错）与 `parseStrict(String) -> SourceFile`（遇首个 ERROR 诊断即抛，语义对齐 `CstToAstMapper.mapStrict`）；另暴露 `tokenize(String) -> List<GdToken>` 供测试与后续 LSP 使用——返回 **raw 扫描流**（无 parser 的 multiline 干预），可能含上下文相关的缩进诊断，不作为合法源「零 ERROR」的判定依据；parser 实际消费的 token 流以 `parse` 内部为准。每次 parse 独立状态，Facade 可安全共享并发调用（对齐 `GdParserFacade` 契约）。

### 3.2 数据流

```text
GDScript Source (String)
  -> GdScriptParserFacade
  -> GdTokenizer (token 流 + 注释 trivia + tokenizer 诊断)
  -> GdScriptParser (Pratt + panic-mode 恢复)
  -> SourceFile AST + AstDiagnostic list (AstMappingResult)
  -> (可选) AstSexprSerializer / AstSexprDeserializer
```

tree-sitter 数据流（`GdParserFacade -> CstAdapter -> CstToAstMapper`）保持不变，两条路径在 `frontend.ast` / `frontend.serialize` 汇合。

### 3.3 实施约定

- 遵循 AGENTS.md：record/密封接口、`var`、`List.of`、`List.getFirst/getLast`、switch 表达式、文本块、`///` 文档注释、record 字段不写注释（用类级 `@param`）、`@Nullable`/`@NotNull`、`requireXXX`/`checkXXX` 命名。
- 移植代码在关键机制处用 `///` 注释标注对应的 Godot 源文件与函数名（如 `/// Port of GDScriptTokenizerText::check_indent`），保证双向可追溯；注释须自解释，不引用计划文档编号。
- 不修改 build 脚本；`module-info.java` 的 exports 调整属于源码范畴。

## 4. 移植范围清单

| 类别 | 内容 | 处置 |
| --- | --- | --- |
| 移植 | token 类型全集、缩进/换行/续行机制、字面量与字符串全形态、Pratt 表达式、全部语句/声明解析、panic-mode + synchronize 恢复（三处接入点）、suite 空语句计数上限、multiline push/pop（类型解析继承调用上下文、不自行开启）、lambda 缩进快照机制 | 行为对齐 Godot |
| 改造 | 注释（Godot 不入 token 流 → 本项目保留 trivia 并分类为 `CommentStatement`/`RegionDirectiveStatement`） | 偏差 D2，§5.4 |
| 改造 | 负号吸收进数字字面量 | 不吸收，偏差 D1，§5.5 |
| 改造 | 类体 `pass` 保留 `PassStatement`（Godot 不产出节点）；`PI`/`TAU`/`INF`/`NAN` 产出 `IdentifierExpression`（不折数值） | parity 适配，§5.2/§5.3 |
| 改造 | `elif`（Godot 嵌套 `IfNode` → AST `ElifClause`）、后缀链（`SubscriptNode`/`CallNode` → `AttributeExpression`+steps/`CallExpression`/`SubscriptExpression`） | AST 形状适配，§5.2 |
| 裁剪 | 语义分析、类型解析、常量折叠（含内置常数数值化）、符号表、补全、注解验证、警告、文档提取、依赖缓存、二进制路径 | 不移植 |
| 保留不动 | tree-sitter 全链路、`frontend.cst`、`frontend.lowering`、`frontend.serialize` | 零行为变更 |

## 5. AST 产出契约与差异决策

### 5.1 位置契约

- `Range`：UTF-8 字节偏移 + 0-based 行、字节列，与 tree-sitter 输出逐位一致。
- `Range` 的唯一事实源是**物理字节游标**：tokenizer 内部按 Godot 约定维护 1-based 行/列（列按字符计）仅供诊断消息；产出 AST 前经 `SourceTextIndex` 由字节偏移统一换算；Godot 在 EOF 合成的 token 行列不参与换算，锚定物理 EOF（§2.2 EOF 坐标条款）。换算逻辑单测覆盖（含 CRLF、中文、emoji、tab、无末尾换行、末尾注释、末尾多行字符串）。

### 5.2 AST 形状适配（Godot AST → 本项目 AST）

以下形状均已用现有 tree-sitter 路径 dump 实证（标注「已钉死」），parity 测试据此断言：

- `elif` 链：Godot 在 false suite 中嵌套 `IfNode`；产出时折叠为 `IfStatement.elifClauses`（`ElifClause(condition, body)`）+ 可选 `elseBody`，范围与 tree-sitter 路径一致（已钉死）。
- 构造器：名为 `_init` 的 `func` 产出 `ConstructorDeclaration`（无 `name`/`isStatic` 组件），其余函数产出 `FunctionDeclaration`——对齐 mapper 的 `constructor_definition` 分支。构造器可显式声明返回类型：无 `->` 时 `returnType = null`，有 `-> T` 时保留对应 `TypeRef` 及源范围（两种形态各钉死一条用例；fixtures 已含 `func _init() -> void:` 形态）。
- 后缀链：Godot 逐节点构造 `SubscriptNode`/`CallNode`；产出时按现有路径形状折叠（已钉死）：`obj.a.b(1).c[2]` → 单个 `AttributeExpression(base, [AttributePropertyStep("a"), AttributeCallStep("b", [1]), AttributeSubscriptStep("c", [2])])`；`arr[0]`（下标直挂 primary）→ `SubscriptExpression(base, [index])`；`f(x)` → `CallExpression(callee, args)`。折叠边界以 tree-sitter 路径产出为准，parity 测试钉死。
- `super`：`super.foo()` → `AttributeExpression(IdentifierExpression("super"), [AttributeCallStep("foo", args)])`；`super()` → `CallExpression(IdentifierExpression("super"), args)`（已钉死；不引入 `is_super` 类标记）。
- 推断类型标记 `:=`：`var`/`const`/参数的 `COLON`+`EQUAL` 识别为推断标记，`type` 组件为非空 `TypeRef`——`sourceText` 为从 `:` 起至 `=` 止的源切片（仅去首尾空白；紧邻写法为 `":="`，`: =` 形态保留内部空白为 `": ="`），range 覆盖整个标记含内部空白——现有路径对 grammar `inferred_type` 字段直接 `mapTypeRef`（trim 源切片）的实证结果（已钉死），**不是** `null`。
- `VariableDeclaration.sourceNodeType`：契约字符串，`var` → `"variable_statement"`、`const` → `"const_statement"`，照抄现有路径取值（序列化会比较该组件）。
- `not in`：产出 `BinaryExpression("not in", left, right)`（已钉死）；Godot 的 `NOT` infix 规则在产出层折叠为该形态（与 `is not` 同理）。
- `assert`：语句位置的 `assert(cond, msg)` 产出 `AssertStatement(condition, message)`，不是 `ExpressionStatement`（对齐 mapper 的 call 提升与 Godot 的 `parse_assert`，已钉死）。
- Lua 风格字典 key：`{ name = 1 }` 的 key 产出**合成** `LiteralExpression("string_name", "&\"name\"")`（源中不存在的拼接文本，range 以现有路径产出为准）——对齐 mapper 846-850 行分支；Python 风格 `key: value` 的 key 正常映射。
- 类体 `pass`（偏差 D4）：产出 `PassStatement`（Godot 不产出节点；现有路径保留，已钉死）。
- key-only 字典 pattern（如 `{"name"}:`）：现有路径丢弃 key 并发 `WARNING` 诊断（`DictEntry` 无法表达缺失 value，已钉死）。v1 对齐该行为（丢弃 + WARNING），登记为 AST 表达力缺口，未来随 AST 演进补全。
- 函数/信号/参数：`FunctionDeclaration`/`SignalStatement`/`Parameter`（含 `variadic` 对应 Godot rest 参数 `...`）。
- 缺失必需表达式：沿用 mapper 政策——产生零宽 `ErrorExpression` 占位（位于所属构造起点）+ ERROR 诊断，不留 `null` 洞（`IfStatement.condition` 等非空组件）。
- 恢复占位：Godot 的 recovery suite/空节点 → 空 `Block`（零宽或实际范围，以 parity 为准）或 `ErrorStatement`/`ErrorExpression`（`CstIssueKind.ERROR`）；`MISSING` 用于「期望 token 缺失」类诊断（如 `Missing identifier`）。

### 5.3 字面量 kind 词汇表（AST 契约）

`LiteralExpression.kind` 固定取值集合（沿用 tree-sitter 节点名，tree-sitter 路径不变）：`integer`、`float`、`string`、`string_name`、`node_path`、`true`、`false`、`null`。`sourceText` 保留源切片（含引号/前缀），与 mapper 现状一致；Lua 风格字典 key 的合成 `string_name` 文本见 §5.2。

内置常量 `PI`/`TAU`/`INF`/`NAN`：**不进入**该词汇表。Godot 将其折为数值 `LiteralNode`，但现有路径产出 `IdentifierExpression`（已钉死）；新 parser 对这些 token 同样产出 `IdentifierExpression` 以维持 parity（数值折叠属裁剪项）。

### 5.4 注释与 region 指令（偏差 D2）

- tokenizer 将注释作为 trivia 收集（文本含前导 `#`、范围、是否整行/行尾），不进入 token 流。
- 插入规则（已用现有路径 dump 钉死）：注释按其源位置插入**所属 suite/类体的语句序列**，作为独立 `CommentStatement`；行尾注释位于其所跟随语句**之后**（同级后继语句），整行注释按源顺序占位。示例：`var x = 1 # tail` → 类体语句序列 `[VariableDeclaration, CommentStatement("# tail"), ...]`；函数体内整行注释按源位置排在相邻语句之间。
- `#region <label>`/`#endregion` 注释分类为 `RegionDirectiveStatement("start"/"end", label)`（`label` 为关键字后文本去首尾空白，空则为 `null`；`#endregion` 的 label 恒为 `null`；`#regionfoo` 也算 start、label `"foo"`，`# region` 带空格则为普通注释——均已用旧路径 dump 钉死）。
- 丢弃规则（两路径一致，已用旧路径 dump 钉死）：套件头行尾注释（`func f(): # c`、`if x: # c`、`else: # c` 等冒号后同行注释）不进入任何语句序列；match 体分支层级的整行注释同样被丢弃（分支 suite 内的注释按常规归属）。
- dedent 前尾窗口的整行注释归属：注释缩进 ≥ 所属 body 缩进时留在 body 内（如 `func f():\n\tpass\n\t# inner\nvar x = 1\n` 中 `# inner` 留在 f 体内），否则归属外层序列（如 `func f():\n\tpass\n# hello\nfunc g():...` 中 `# hello` 归属顶层）；body 中间的整行注释只要下一行代码仍属于该 body 即留在 body 内（col 0 亦然）。
- 表达式内部注释（如数组字面量元素间的 `[\n\t1, # one\n\t2\n]` 或调用/注解实参列表内）：旧路径产出 `UnknownExpression`（nodeType `comment`）+ WARNING；新 parser 的注释不进入 token 流、无法复现该形状，登记为已知 parity 差异——新 parser 静默忽略该类注释（实现：分组闭合时记录 span，各排水点跳过 span 内注释），parity 语料排除此类片段。
- dedent 尾窗口的混合缩进注释组（col-0 注释与缩进注释相邻出现在 body 末尾，如 `func f():\n\tpass\n# low\n\t# high\nvar after = 1\n` 或多层嵌套 `class Outer:\n    class Inner:\n        pass\n# low\n        # high\nvar after = 1\n`）：旧路径 scanner 对 col-0 注释做下一行 lookahead 回填缩进（上游 issue #85/#42 承认的启发式缺陷），把整组注释回填进 body；新 parser 应用一致的缩进阈值规则，整组归属外层序列。该差异在**单层与多层 dedent 下均出现**（最小单层样例即上例；另有三层嵌套与顺序变体样例实证），登记为已知 parity 差异，parity 语料排除此类片段；孤立的单条注释（col-0 外出、缩进保留、body 中间 col-0）两路径一致，不受影响。
- Phase 4 对 fixtures 全量注释归属做 parity 验证。

### 5.5 负号吸收（偏差 D1）

- Godot tokenizer 在一元位置将 `+`/`-` 吸收进数字字面量（使 `-2 ** 2` 解析为 `(-2) ** 2`）；本项目 AST 契约将符号建模为 `UnaryExpression("-", operand)`（现有 serde 测试已钉死该形状）。
- 决策：**不吸收**，`-`/`+` 始终作为独立 token，parser 按 `PREC_SIGN` 解析一元运算。已钉死：现有路径对 `-1`、`-2` 等一律产出 `UnaryExpression`，普通一元场景两路径一致。
- 不吸收时 `-2 ** 2` 在 Godot 优先级下解析为 `-(2 ** 2)`（一元操作数只按 `PREC_SIGN` 解析，`PREC_POWER` 更高），而现有路径产出 `(-2) ** 2`——该分歧并入 D3 优先级分歧集（§5.8），不再单列。

### 5.6 诊断对齐政策

- 语法错误消息语义对齐 Godot（可精简 `vformat` 细节）；3.x 移除关键字给出迁移提示，与 mapper 现有消息方向一致。
- Godot 在 parse 阶段的「语义型」检查（重复参数名/重复成员名、静态函数中 `self`、构造器返回值、循环外 `break`/`continue`、默认参数顺序、pattern bind 冲突等）：首版**仅移植无歧义的纯语法判定**（重复参数名、默认参数顺序、`break`/`continue` 循环上下文、构造器 `return` 带值）；依赖符号表/类型的一类一律裁剪。每条移植的检查须配单测。
- Phase 3 识别并**暂缓**的 parse 时检查（均属依赖节点类型/上下文的语义型判定，留给分析层；新 parser 产出结构、不报诊断）：赋值目标类型检查（`Only identifier, attribute access, and subscription access can be used as assignment target.`）、调用目标类型检查（`Cannot call on an expression. Use ".call()" if it's a Callable.`，故 `f(1)(2)`、`$Node(x)`、`PI()` 等新 parser 不报错）、隐式 `super()` 函数上下文检查（`Cannot use implicit "super" call outside of a function.`）。暂缓行为由 `assignmentTargetCheckIsDeferredByDesign` 等测试钉死。
- tokenizer 错误（非法字符、未闭合字符串、缩进混用等）进入诊断列表，severity 为 ERROR，不中断扫描。

### 5.7 parity 范围定义

parity 语料分三类管理（非法源不适用 parity，另有独立验收规则）：

- **(i) 共同支持且结构一致的语法**：双路径 AST 经 `AstSexprSerializer` 序列化后**逐字符一致**（含全部 range）。13 个 fixtures 均属此类（现状：全部零诊断、零 `Unknown*` 节点，由 `CstToAstMapperTest` 断言；Phase 0 审计确认不含 §5.8 分歧组合）。
- **(ii) Godot 合法但旧路径报错/丢失的语法**：不作 AST parity 要求；新 parser 按 Godot 产出，或登记信息丢失。已实证登记：
  - `$%Child`、`$Root/%Child`（`%` 位于 `$` 后或 `/` 后）：Godot 合法，旧路径报 ERROR——新 parser 按 Godot 产出 `GetNodeExpression`。
  - key-only 字典 pattern（`{"name"}:`）：旧路径丢弃 key + WARNING——v1 对齐该行为并登记为 AST 表达力缺口（§5.2）。
  - 脚本前缀/类体中的独立文档字符串（如 `"""doc"""\nclass_name X\n`、`var x = 1\n"""doc"""\nvar y = 2\n`）：Godot 将其消费为文档字符串、不产出节点；旧路径产出 `ExpressionStatement(LiteralExpression("string"))`——新 parser 按 Godot 落地（不产节点），parity 语料排除此类片段（Phase 2 探针实证，fixtures 不含此形态）。
  - 嵌套类缩进体首行的 `extends`（`class Inner:\n\textends Base\n\tpass\n`）：Godot 将其设为类超类、不产出节点；旧路径在 body 内产出 `ExtendsStatement` 且 `extendsTarget = null`——新 parser 按 Godot 落地（`extendsTarget = "Base"`），parity 语料排除此类片段（Phase 2 探针实证，fixtures 不含此形态）。
  - match 体 `pass` 分支（`match v:\n\t1:\n\t\tpass\n\tpass\n`）：Godot 消费 `pass` 且不产出分支；旧路径整体报 ERROR——新 parser 按 Godot 处理，parity 语料排除（Phase 4 探针实证）。
  - match 层级注解：`@warning_ignore(...)` 两路径均消费并丢弃（旧路径静默、Godot 挂到分支后由本项目 AST 丢弃）；其他注解（如 `@export`）Godot 报 `Annotation "%s" is not allowed in this level.`，旧路径静默丢弃——新 parser 按 Godot 报错，parity 语料排除（Phase 4 探针实证）。
  - 纯注释套件体（`func f():\n\t# only\nvar x = 1\n`）：注释行不建立缩进，Godot/新 parser 报 `Expected indented block...`；旧路径接受并把注释作为 body 唯一内容——parity 语料排除（Phase 4 探针实证）。
  - `assert` 超过两个实参（`assert(1, "m", 3)`）：Godot/新 parser 报 `Expected ")" after assert expression.`；旧路径静默丢弃多余实参——parity 语料排除（Phase 4 探针实证）。
  - 类体/顶层匿名函数（`func(): pass\n`）：Godot/新 parser 报 `Expected function name after "func".`；旧路径接受并产出 `FunctionDeclaration("<anonymous>")`——parity 语料排除（Phase 4 探针实证）。注：**suite 内**的语句级 `func`（命名或匿名）两路径行为一致——均产出 `FunctionDeclaration`（匿名时 `"<anonymous>"`），这是 AST 契约形状而非分歧。
  - `static` 与成员之间换行（`static\nvar x = 1`、`static\n# c\nvar x = 1`）：Godot/新 parser 立即报 `Expected "func" or "var" after "static".`（vendor 逐字如此）；旧路径接受并把 `static` 跨进成员节点（其间注释随之丢弃）——parity 语料排除（Phase 4 探针实证）。
  - match 体分支间 col-0 整行注释（`match v:\n\t\t1: pass\n# low\n\t\t2: pass\n`）：Godot 视注释为 trivia 正常解析；旧路径 grammar 报 ERROR——新 parser 按 Godot 解析（注释随 match 分支层丢弃），parity 语料排除（Phase 4 探针实证）。match 体**尾部** col-0 注释（后续代码 dedent）旧路径正常并归属外层，两路径一致。
- **(iii) 运算符优先级分歧组合**：见 §5.8（D3 已确认与 Godot 锚定）；parity 矩阵对这些组合断言 Godot 结构、不作双路径一致要求。
- **非法源**（独立验收，不参与 parity 分类）：不要求 AST 一致；要求新 parser (a) 不抛异常、可终止，(b) 产生 ≥1 条 ERROR 诊断且 range 有效，(c) synchronize 后的兄弟声明结构正确（如类体中一个坏函数之后的函数完整解析）。
- 已知差异白名单：仅本条 (ii)(iii) 登记项；新增豁免须在 §5 中登记原因与样例。

### 5.8 决策 D3：运算符优先级分歧（已确认：与 Godot 锚定）

实证三方分歧（Godot `PREC_*` vs 固定版 tree-sitter grammar，已用现有路径 dump 对照确认）：

| 合法表达式 | 现有路径 AST（tree-sitter） | Godot 结构 |
| --- | --- | --- |
| `2 * 3 ** 2` | `(2 * 3) ** 2`（`**` 与 `*` 同级、左结合） | `2 * (3 ** 2)`（`PREC_POWER` 高于 `PREC_FACTOR`） |
| `-x ** 2` | `(-x) ** 2`（一元高于幂） | `-(x ** 2)`（`PREC_SIGN` 低于 `PREC_POWER`） |
| `-2 ** 2`（D1 不吸收时） | `(-2) ** 2` | `-(2 ** 2)` |
| `1 + 2 as int` | `1 + (2 as int)`（`as` 高于加法） | `(1 + 2) as int`（`PREC_CAST` 低于加法） |

**已确认采用 (a)**：新 parser 的 `PREC_*` 优先级表与 Godot 锚定；上表组合在 parity 矩阵中标注为「Godot 正确性断言」而非 parity 断言。13 个 fixtures 经审计不含分歧组合（`**` 仅出现于 `x ** 2`/`a ** b`/`2 ** 3 ** 2` 等两路径同构形态，`as` 左操作数均为 primary），fixture parity 不受影响。tree-sitter 路径不做任何调整，分歧作为其已知缺陷登记。

## 6. 分阶段实施

### Phase 0：工程基线与测试设施（P0）

任务：

- 新建 `frontend.gdscript` 包骨架与 `module-info.java` exports。
- 实现 `SourceTextIndex`（行起始表、字节偏移↔行列换算、物理 EOF 锚定）与 `GdTokenType`/`GdToken`（含 ERROR 消息载体）。
- 建立 parity 测试基类：同一 source 分别走双路径，序列化比较；新侧直接驱动 `GdScriptParser`（不等 facade），本阶段以骨架类占位。
- 用现有路径 dump 关键语料的 AST 参照（序列化文本存入测试资源，供 Phase 3-5 比对）；§5.2/§5.4 已钉死的形状直接转写为断言。
- 审计 13 个 fixtures 的运算符组合与语法形态，确认全部落入 §5.7 (i) 类（已初步完成：无 `**`×一元/乘法混排、`as` 左操作数均为 primary、无 `not in`/`$%`；审计结果固化为 parity 测试的前置断言）。

验收标准：

- `SourceTextIndexTest`（ASCII/中文/emoji/CRLF/tab、无末尾换行、末尾注释、末尾多行字符串）通过：`script/run-gradle-targeted-tests.sh --tests SourceTextIndexTest`。

实施状态（2026-10-06 完成）：

- 包骨架与 exports：✅ `frontend.gdscript` 已建立并加入 `module-info.java` exports。
- `SourceTextIndex`：✅ 完成（行起始表、字节偏移↔行列、物理 EOF 锚定、越界拒绝）。
- `GdTokenType`：✅ 完成，逐一对齐 Godot `Token::Type`（`TK_CONST/TK_IN/TK_VOID/TK_EOF` → `CONST/IN/VOID/EOF`，省略 `TK_MAX`），含 `token_names` 对照 displayName。
- `GdToken`：✅ 完成（类型、源切片、`GdLiteralKind`、ERROR 消息载体、起止字节偏移、1-based 行列）。
- parity 测试基类：✅ `GdParserParityTestSupport`（双路径驱动、序列化比较、fixture 发现）；新侧 `gdScriptSexp` 为占位骨架，待 Phase 2 接入 `GdScriptParser`。
- AST 参照 dump：✅ 12 个语料快照存于 `src/test/resources/gdparser-parity/*.sexp`；§5.2/§5.4 钉死形状转写为 `GdParityReferenceTest` 结构断言。
- fixtures 审计：✅ `GdFixtureParityAuditTest`（`**`×因子/一元、`as` 右操作数、`not in`、`$` 下 `%` 规则）；审计发现 Sample5.gd 含裸 `%name` 表达式——该形态两路径均支持，非 (ii) 分歧，审计规则已相应细化。
- 验收：✅ `SourceTextIndexTest` 通过（`bash gradlew test --tests SourceTextIndexTest`；`script/run-gradle-targeted-tests.sh` 因 gradlew 无执行权限暂不可用，下同）。

### Phase 1：Tokenizer 移植（P0）

任务：

- 移植 `scan()` 全分派：标识符/关键字/注解、数字全形态、字符串全形态（含 raw/StringName/NodePath/三引号）、运算符与标点、`VCS_CONFLICT_MARKER` 等改进 token。
- 移植缩进栈与 `pending_indents` 计数器（含不对齐 dedent 压栈不 `++`）、pending newline/INDENT/DEDENT、multiline 标志、反斜杠续行、EOF 合成（物理坐标锚定，§5.1）。
- 移植 error 队列（ERROR token 优先返回、消息入 token、扫描继续）；注释 trivia 收集（D2）。
- 提供缩进状态快照 push/pop（对齐 `push_expression_indented_block`，供 Phase 4 lambda 使用）。
- 不吸收符号（D1）；`_` → `UNDERSCORE`；`true`/`false`/`null` → LITERAL；`PI`/`TAU`/`INF`/`NAN` 常量 token。

验收标准：

- `GdTokenizerTest` 通过，覆盖：缩进序列与混用报错、空行/纯注释行、续行、multiline 抑制、全部字面量形态、非法字符后继续扫描、CRLF、无末尾换行/末尾注释/末尾多行字符串的 EOF 坐标。
- 对 13 个 fixtures 全量 tokenize 无 ERROR token、无异常。
- 运行：`script/run-gradle-targeted-tests.sh --tests GdTokenizerTest`。

实施状态（2026-10-06 完成）：

- `scan()` 全分派：✅ 标识符/关键字（36 个关键字 + `INF`/`NAN`/`PI`/`TAU` 4 个内置常量）/注解、数字全形态、字符串全形态（raw/StringName/NodePath/三引号/转义/UTF-16 代理对校验）、运算符与标点、`VCS_CONFLICT_MARKER`/`BACKTICK`/`QUESTION_MARK`。
- 缩进状态机：✅ indent 栈 + `pendingIndents` 有符号计数（不对齐 dedent 压回不 `++`）、pending newline、multiline 抑制、反斜杠续行（含续行后注释行跳过）、EOF 合成（含「消费最后字符触发 EOF 钩子」的行号双增 quirk 与最后 token end 坐标落非物理行 quirk，均以测试钉死）；合成 token 字节范围锚定物理 EOF。
- 错误队列：✅ LIFO 弹出、ERROR token 优先返回、消息入 token、扫描继续。
- 注释 trivia：✅ `GdComment`（文本含 `#`、字节范围、整行/行尾标记），不进 token 流。
- 缩进快照 push/pop：✅ 对齐 `push/pop_expression_indented_block`；空栈 pop 抛 `IllegalStateException`（Godot 为静默 no-op，此处按内部 bug 处理）。
- D1 不吸收符号：✅ `+`/`-` 恒为独立 token；`last_token`/`can_precede_bin_op` 随之裁剪。
- 移植偏差记录：字符分类用 JDK `Character.isUnicodeIdentifierStart/isUnicodeIdentifierPart` + `_` 近似 Godot XID 表（并显式排除越界哨兵 0——JDK 将 NUL 等 identifier-ignorable 控制符判为标识符字符，否则 EOF 处死循环）；字面量不解码值（`GdLiteralKind` + 源切片承载，对齐 AST 契约）。
- 审阅修复（review-expert-a/c 复核确认）：`scan()` 续行递归改循环（JVM 无尾调用消除，防栈溢出）；`ERROR` token 源切片置空（字节区间精确，防 O(n²) 内存，Godot 的 CoW String 无此问题）；无参构造器初始化空源不变量；未配对 UTF-16 代理项按 JDK UTF-8 替换规则计 1 字节（与 tree-sitter 路径字节流一致）；续行错误文案与 vendor 逐字符对齐；key-only pattern 诊断精确断言；补 tokenizer 级多行字符串 EOF 字节锚定、10k 连续续行、20k 错误密度、`\` 后非换行报错等边界用例。
- 验收：✅ `GdTokenizerTest` 74 个用例通过（含 13 fixtures 动态遍历无 ERROR）；`SourceTextIndexTest` 通过；既有前端测试（CstToAstMapperTest、AstSexprSerdeTest 等 14 个测试类）回归通过。

### Phase 2：Parser 骨架与类体声明（P0）

任务：

- token 流 cursor（`advance`/`peek`/`previous`、ERROR token 转诊断并跳过）。
- `panic_mode` + `synchronize()`（同步集与 Godot 一致，`if` 排除）+ `push_error` 累积；三处接入点（program 前缀、类体循环、语句末尾）在本阶段即完成接线。
- 最小表达式子集前置：字面量、标识符、调用、属性、二元运算（声明所需的注解实参/枚举值/默认参数依赖之）；完整 Pratt 在 Phase 3 补全后回归验证本阶段语料。
- `parse_program` 前缀（注解/文档字符串/`class_name`/`extends`，含同行组合）与 `parse_class_body` 成员分派；3.x 移除关键字迁移诊断。
- `parse_suite` 框架（单行/多行、分号、空语句计数上限、空语句跳过）。
- 注解语法解析（参数可跨行、可尾逗号；不验证名称/个数）；`TypeRef` 解析（点链、`Array[int]`、`Dictionary[K, V]`，禁止嵌套容器类型；multiline 继承调用上下文，§2.2 类型解析条款）。
- 变量/常量/信号/枚举/函数签名（默认参数、rest、static、`->` 返回类型、无冒号的无体函数）产出 AST；嵌套类；`_init` → `ConstructorDeclaration`（§5.2）。

验收标准：

- `GdScriptParserTest` 声明层用例通过：`class_name`/`extends` 顺序与同行、嵌套类、static 限定、注解挂接、枚举值、信号参数、函数签名全形态、`_init` 构造器。
- 声明级 parity 仅覆盖无体/空体的内联声明片段（`Sample*.gd` 全文件 parity 在 Phase 4 统一验收）。
- 运行：`script/run-gradle-targeted-tests.sh --tests GdScriptParserTest,GdParserParityTest`。

实施状态（2026-10-06 完成）：

- token 流 cursor：✅ `advance`/`match`/`check`/`consume`/`isAtEnd`；ERROR token 在 cursor 层转诊断并跳过（对齐 Godot `advance` 内层循环，诊断 range 取错误 token 字节区间）。
- panic/synchronize：✅ 同步集与 Godot 逐 token 一致（`if` 排除）、`push_error` 不抑制级联、三处接入点（program 前缀、类体循环、语句末尾）已接线。
- `parse_program`：✅ 注解/文档字符串前缀、`class_name`/`extends` 任意顺序各至多一个、同行 `class_name X extends Y`、前缀结束进入类体、`Expected end of file.` 检查。
- `parse_class_body`：✅ 成员分派全表、`static` 前缀、9 条 3.x 移除关键字迁移诊断（vendor 原文）、`Unexpected %s in class body.`（`get_debug_name` 对齐）；类体 `pass` → `PassStatement`（D4）。
- `parse_suite`：✅ 单行/多行、分号串接、空语句跳过、`error_count++ > 100` 熔断（`Too many statement errors.`）、DEDENT/EOF 退出与 `Missing unindent`/`Expected newline after ";"` 消息。
- 注解：✅ 语法解析（实参跨行 + 尾逗号；不验证名称/目标/个数）；产出 `AnnotationStatement` 按源序置于被注解声明之前；**同行注解计入声明 range**、换行注解独立（探针实证钉死）。
- `TypeRef`：✅ 点链/`Array[E]`/`Dictionary[K, V]`/嵌套容器禁止（`Nested typed collections are not supported.`）/`void` 限制；multiline 继承调用上下文（自身不 push/pop，与 Godot 一致）。
- 声明产出：✅ 变量/常量/信号/枚举/函数签名（默认参数、rest、static、`->` 返回类型、无冒号无体函数）/嵌套类全形态；属性 get/set 块（引用形态与内联套件）完整消费不产 AST；`_init` → `ConstructorDeclaration`；无体函数空 `Block` 的 range = 整个声明 range（对齐 mapper `mapBody(null, fallback)` 实证）；`:=`/`: =` 推断标记为非空 `TypeRef`（sourceText 保留内部空白）。
- 最小表达式子集：✅ Pratt 骨架 + 完整 `PREC_*` 枚举（按 Godot 声明顺序）；字面量/标识符/`self`/内置常量（`PI` 等 → `IdentifierExpression`，§5.3）/一元 `+`/`-`/二元全家族/调用/属性/下标链折叠（§5.2 钉死形状）。
- 裁剪记录（与本阶段范围一致）：枚举值/类成员重名检查、`_static_init` 特判、注解注册表验证、构造器 `return` 带值检查（随 return 语句归于后续阶段）；失败的一元/二元操作数使表达式整体失败（AST 无可空表达式组件，Godot 容忍 null child）。
- range 契约落地：AST range 一律经 `SourceTextIndex` 由物理字节游标换算；多行套件 `Block` 从冒号后 NEWLINE 字节起、末语句止（探针实证）；同行注解纳入声明起点；`SourceFile` 空内容时坍缩为物理 EOF 零宽。
- 新登记 §5.7 (ii) 分歧 2 项（脚本前缀/类体独立文档字符串消费、嵌套类体首行 `extends`），parity 语料相应排除；D3 分歧 `-2 ** 2` 以 Godot 结构断言钉死（不作 parity）。
- 审阅修复（review-expert-a/c 两轮复核确认）：失败表达式语句在 EOF 处守卫 `advance()`（Godot 软失败语义，修复无尾换行坏语句抛 `IllegalStateException`）；移植 `Token::is_identifier()`（`match`/`when`/`PI`/`TAU`/`INF`/`NAN` 可作名字）与属性名的 `is_node_name()` 集合；下标失败路径补齐 `popMultiline` + 右括号消费（修复 multiline 泄漏污染后续声明）；accessor 套件注解隔离为「丢弃内层残留、恢复外层快照」，且 suite/类体退出时本层尾随注解排入本层序列（不泄漏到外层成员）；`static` 错误前缀不再污染 synchronize 恢复的兄弟声明（恢复路径精化，合法源行为不变）；同行 `class_name … extends` 纳入 `extends` 去重检查（反向顺序保持 vendor 语义不报，已钉死）；suite 熔断改为按「核心语句为 null」计数（带注解的失败声明与 Godot 一致）；`pushMultiline` 待丢弃 token 走错误上报扫描（vendor 裸 scan 会静默丢 ERROR token，与 §5.6 tokenizer 错误必入诊断冲突，有意偏差并注释）；property 跨行提示与 vendor 逐字节对齐；移除被 `is_identifier` 归一化覆盖的内置常量专用前缀规则；补 18 个审阅驱动回归用例；合法 parity 语料改用 `assertCleanParity`（AST 一致 + 双端零 ERROR 断言）。
- 后缀链重构（性能债清偿）：后缀循环改为独占单个 `ArrayList<AttributeStep>`、链结束时一次性冻结 `AttributeExpression`（消除每步不可变列表复制的 O(n²)）；token 级消费顺序与 multiline push/pop 时序不变；三个后缀入口合并为 `parseSuffixChain`，参数解析与消息文本不变；续链含与 Godot `while (p <= rule.prec)` 等价的优先级截断检查（review-expert-a 复核确认，当前规则表切片内恒通过）；缺成员名恢复保持旧 per-infix 形状（报错留链、后续调用/下标仍折叠到前一 property step，review-expert-c 复核确认）；重构前补 8 个链形态/错误路径钉死（`f(1).x`、`f(1)(2)`、`a[0][1]`、`a.b(1)(2)`、长链、链内跨行调用、非 EOF 悬点降级），另补 3 个缺名恢复形状钉死，全部测试绿。
- 验收：✅ `GdScriptParserTest` 117 例、`GdParserParityTest` 11 例（声明层 parity 语料，合法语料双端零 ERROR）通过；Phase 0/1 与既有前端测试（14 个测试类）全部回归通过；parity 基类 `gdScriptSexp` 已接入 `GdScriptParser`。

### Phase 3：表达式（P0）

任务：

- Pratt 循环 + 完整 `PREC_*` 枚举 + `get_rule()` 规则表移植（含 `**` 左结合断言：`2 ** 3 ** 2` → `(2 ** 3) ** 2`）。
- 字面量（§5.3 词汇表；`PI` 等产出 `IdentifierExpression`）、标识符/`self`/`super`（§5.2 钉死形状）、数组/字典（Python/Lua 双风格与合成 key、尾逗号、缺失元素恢复占位）。
- 调用/属性/下标后缀链折叠（§5.2）、`$`/`%` 节点路径（含 §5.7 (ii) 的 Godot-only 形态）、`preload`、`as`/`is [not]`/`not in`、三元、赋值全家族（RHS 禁嵌套赋值诊断）、`await`。
- 一元符号按 D1 不吸收落地（§5.5 已钉死）。
- multiline push/pop 按 Godot 各调用点移植（调用/数组/字典/签名/注解实参；类型解析继承调用上下文，§2.2 类型解析条款）。

验收标准：

- 表达式 parity 语料通过，含运算符优先级矩阵抽样（全部二元运算符两两组合 + 一元/幂/三元/赋值交互）；§5.8 分歧组合按 D3 断言 Godot 结构、不作 parity。
- `Sample10_Operators.gd` 全文件双路径 parity 移至 Phase 4 验收（其含语句/注释，依赖 Phase 4 能力）。
- 运行：`script/run-gradle-targeted-tests.sh --tests GdScriptParserTest,GdParserParityTest`。

实施状态（2026-10-06 完成）：

- 规则表：✅ Phase 3 切片全量移植——`not`/`!`/`~` 一元前缀（Godot 实际为统一 `parse_unary_operator`，本方一并合并）、`not in` 中缀（`parse_binary_not_in_operator`）、`if` 三元、`as`/`is [not]`、赋值全家族 12 token、`?` 迁移提示（`parse_invalid_token`）、`await`/`$`/`%`/`preload`/`super` 前缀、分组 `(`/`[`/`{` 前缀；`%` 前缀 get_node 与中缀取余并存（与 Godot 同表）。
- canAssign/stopOnAssign 线程化：✅ `parse_expression(bool, bool)` 双标志按 vendor 调用点逐项对齐（仅表达式语句 `true`，变量/常量/枚举/默认参数/注解实参/下标/调用实参/赋值 RHS/二元 RHS/三元分支/await 操作数全 `false`）；`Assignment is not allowed inside an expression.` 在 `!canAssign` 时命中（链式 `a = b = 1`、初始化器与调用实参中的赋值均按此诊断，已钉死）。
- 字面量容器：✅ 数组（尾逗号、跨行、缺元素报错跳过不占位）、字典（Python/Lua 双风格、首条目分隔符决定风格、混用报错、Lua key 归一化为 `string_name` 字面量 `&"name"`/`&`+原字符串文本、key-only/缺值以零宽 `null` 字面量占位——Godot dummy recovery `LiteralNode` 的 AST 对应、双侧皆缺则丢条目）；vendor 混用分隔符后多消费一个 token 的 quirk 已逐字镜像并以混用测试钉死。
- 节点路径：✅ `$`/`%` 全状态机（名称/`"字符串"`、`/` 绝对与分段、`%` 仅段首或 `/` 后），§5.7 (ii) 形态 `$%Child`/`$Root/%Child` 按 Godot 产出；sourceText 存原始切片（含 `$`/`%` 前缀），与 mapper `text(node)` 契约一致。
- 其余形态：✅ `preload`（路径为一般表达式、允许一个尾逗号）、`await`（操作数 `PREC_AWAIT`，后缀链纳入操作数）、`super`（`super()`→`CallExpression(Identifier "super")`、`super.m(...)`→单 call step 的 `AttributeExpression`，方法名走 `consume(IDENTIFIER)` 即 `is_identifier()` 集合——`super.match()` 合法、普通关键字拒绝，review-expert-c 复核确认）、`as`（右侧 `parse_type`，缺类型降级为左操作数）、`is`/`is not`（`negated` 标志承载，非 Godot 的外层一元包裹——AST 契约形状）、三元（`ConditionalExpression(condition, left=true, right=false)`，缺 `else`/缺分支按 vendor 消息）、赋值（op 文本取 token 源切片；`Expected an expression after "=".`）。
- 分组与 range 契约：✅ 分组去包装（内部节点保自身 range）；**外围节点 range 覆盖实际消费 span**（含分组括号）——`parsePrecedence` 帧记录首 token 起点（`extentStart`）并传入 infix，右缘取最后消费 token 而非子节点 range；`GdParserParityTest.groupingRangeParity`（`(f)(1)`、`await (x)`、`{(a): 1}` 等）字节级钉死（review-expert-c 复核确认）。
- multiline 移植：✅ `parsePrecedence` 入口对 `(`/`[`/`{` 前缀按 vendor 位置（消费前）push；各构造闭括号前 pop（分组失败也 pop，与 vendor 一致）；类型解析继续继承调用上下文。
- 暂缓登记（§5.6）：赋值目标类型检查、调用目标类型检查（`Cannot call on an expression...`）、隐式 `super()` 上下文检查——均留分析层，已有钉死测试。
- 审阅修复（review-expert-a/c 复核确认）：`super` 方法名判定改用 `consume(IDENTIFIER)`（原严格 token 类型检查错误拒绝 `match`/`when`/内置常量；vendor `check(IDENTIFIER)`→`is_identifier()` 实证）；分组 extent 线程化（外围节点 range 覆盖分组括号，见上条）；`parsePrecedence` 分组 push 后在 prefix 缺失路径补 pop（当前不可达，防规则表回归）；补 EOF 悬挂（`(\n`/`{\n`/`foo(\n`）、`f(,)`/`f(1 2)` 恢复、`{1 2}` 缺分隔符保留键值对（vendor `decided_style` 无条件置位，`gdscript_parser.cpp:3285`）、`super.if` 负例等回归用例。
- 验收：✅ `GdScriptParserTest` 150 例（新增 33 例：容器/节点路径/preload/await/super/as-is/三元/not-in/一元家族/赋值正反两方）、`GdParserParityTest` 新增容器/专用形态/赋值/优先级矩阵抽样 4 组 parity 语料全部一次通过；18 个既有测试类回归全绿。

### Phase 4：语句、控制流与 trivia（P0）

任务：

- `if`/`elif`/`else`（§5.2 折叠）、`while`、`for`（迭代变量可选类型）、`match`（字面量/绑定/通配/`..`/数组/字典嵌套 pattern、key-only 字典 pattern 登记行为、逗号多 pattern、`when` guard、`pass` 分支）。
- `return`/`pass`/`break`/`continue`/`breakpoint`/`assert`/表达式语句。
- lambda（`func` 前缀表达式、可选名、单行/多行体、`lambda_ended` 状态、multiline 上下文保存恢复、缩进快照 push/pop 与退出时多余 `DEDENT`/`INDENT`/`NEWLINE` 跳过规则，§2.2 lambda 条款）。
- 属性 get/set 块消费（内联套件与 `get = name, set = name` 引用形态；不产出 AST，§2.1）。
- 注释 trivia → `CommentStatement`/`RegionDirectiveStatement` 按 §5.4 已钉死规则插入；类体 `pass` 产出 `PassStatement`（D4）。

验收标准：

- 13 个 fixtures 全文件双路径 parity 通过（§5.7 (i) 类；白名单仅限 §5.7 已登记项）。
- `Sample10_Operators.gd` 全文件 parity 在此一并验收；lambda/match/属性块/region 专测通过。
- 运行：`script/run-gradle-targeted-tests.sh --tests GdScriptParserTest,GdParserParityTest`。

实施状态（2026-10-07 完成）：

- 控制流语句：✅ `if`/`elif`/`else`（§5.2 折叠，`elif` 链由循环消费、缺条件时整链消费但不出节点）、`while`、`for`（可选 `: type`，两条语境化 `in` 错误消息）、`return`（含 lambda 形态的 `in_lambda && !is_statement_end_token()` 补解析分支）、`break`/`continue`/`breakpoint`、`assert`（括号形态、可选 message、允许一个尾逗号；三参按 Godot 报 `Expected ")" after assert expression.`，登记 §5.7 (ii)）。语句/子句/套件 range 全部按旧路径 dump 钉死（body 块始于冒号后 NEWLINE、单行套件始于首语句、`elif` 子句始于 `elif` 关键字、外围语句终于最后套件尾）。
- 移植检查（§5.6）：✅ `break`/`continue` 循环上下文（`canBreak`/`canContinue` 围绕循环套件 save/enable/restore，循环后自动复原）、构造器 `return` 带值（`currentFunctionName` 跟踪 `_init`/`_static_init`，报错后值表达式照常解析保留）。
- 测试：✅ `GdScriptParserTest` 新增 14 例（形状/range/恢复/正反诊断），`GdParserParityTest` 新增 `controlFlowParity`/`suiteShapeParity` 两组；既有 `badStatementInSuiteThenSiblingRecovers` 因 `if` 成为合法语句关键字而按 Godot 新行为更新断言（裸 `if` 现报 `Expected conditional expression after "if".`，兄弟恢复不变）。
- match：✅ `parseMatch`/`parseMatchSection`/`parseMatchPattern` 全量移植——字面量/表达式（一般表达式解析，`false` 禁赋值）、`var x` 绑定（`PatternBindingExpression`，range 覆盖 `var x`）、`_` 通配（普通标识符 AST）、`..` rest（仅容器内合法，转为 `openEnded`；分支层级/rest 作值/rest 后还有元素均按 vendor 消息报错）、数组/字典嵌套 pattern、逗号多 pattern、`when` guard。match 体 `pass` 按 Godot 消费不产节点（登记 §5.7 (ii)）；match 层级注解仅 `@warning_ignore` 消费丢弃、其余报 `Annotation "@%s" is not allowed in this level.`（登记 §5.7 (ii)）；match 体分支层级注释丢弃（两路径一致）。key-only 字典 pattern 丢 key + 单条 WARNING（nodeType 镜像旧路径 CST 标签，如 `"string"`），已登记表达力缺口。bind 冲突族检查（多 pattern+bind、重复 bind、遮蔽局部变量）随 §5.6 暂缓。match 语句 range 终于最后分支（不含 DEDENT，实证修复）。
- 测试：✅ `GdScriptParserTest` 新增 11 例（全 pattern 形态/归属/range/恢复/正反诊断）、`GdParserParityTest` 新增 `matchParity`（含 key-only 与 `@warning_ignore`——两路径丢同样信息故 AST 仍逐字节一致）。
- lambda：✅ `parseLambdaLike` 全量移植——可选名字、签名复用（`forLambda` 参数化冒号为必需，`Expected ":" after lambda declaration.`）、`pushMultiline(false)` 隔离 + multiline 上下文时 `pushExpressionIndentedBlock` 快照、体结束后按 vendor 顺序 pop 并 raw-skip 多余 DEDENT/INDENT/NEWLINE（不动 `previous`）再 `popExpressionIndentedBlock`；`inLambda`/`lambdaEnded` 全状态接入 `isStatementEnd()`/`endStatement()`（含 vendor `if (!found)` 守卫——仅在未消费真实终止 token 时才标记 lambda 结束，否则多语句 lambda 体次行语句会被切断，已实证）；`parseSuite(context, forLambda)` 的 DEDENT consume/match 分岔与循环 `!lambdaEnded` 条件按 vendor 落地；lambda 内禁 break/continue（save/restore），`return` 的 lambda 补解析分支（`g(func(): return)` 为 bare return 不报错）。**语句级 `func` 按旧路径契约产出 `FunctionDeclaration`（匿名时 `"<anonymous>"`）** 并随后 `endStatement("expression")` 消费 lambda-end 状态；类体匿名函数按 Godot 报错（登记 §5.7 (ii)）。
- 尾随 lambda extent：✅ Godot 与 tree-sitter 对 lambda 尾部 extent 存在结构分歧（lambda 体内层 `end_statement` 已消费后续 NEWLINE/DEDENT，Godot 将其算入外层 extent、tree-sitter 不算）——按 parity 锚定引入 `trailingLambdaEndByte` 覆盖（lambda 记录 body 尾，后续任意 `advance()` 失效），作用于 var/const/return/表达式语句/赋值/二元/三元/一元/`not in`/`await` 的右缘取值，分组/调用/容器等后续 token 场景不受影响。
- 测试：✅ `GdScriptParserTest` 新增 10 例（单行/多行/命名/默认值/括号内/多行括号内/语句级三种形态/类体匿名负例/break 状态/bare return/头部错误），`GdParserParityTest` 新增 `lambdaParity` 9 条。
- 注释/region trivia：✅ §5.4 规则全量落地——`materializeComment`（`#endregion` 优先于 `#region` 前缀判定；start label 去空白、空为 null、end 恒 null；`#regionfoo`/`# region` 边界钉死）；三源合并排水 `drainAnnotationsAndComments`（注解+两类注释按字节序，处理 `@export\n# note\nvar` 与 `# note\n@export\nvar` 两种交错）、`drainTrailingComments`（语句/成员后紧跟）、`drainTailWithAnnotations`（dedent 尾窗口按缩进阈值归属，含悬挂注解字节序合并）；套件头行尾注释（`func f(): # c`/`if x: # c`/`else: # c`/`class Inner: # c`/`match v: # c`）与 match 分支层级注释按两路径一致行为丢弃；**分组闭合 pop 丢弃组内注释**（`popMultiline(discardInternalComments)`——表达式内部注释按登记差异静默忽略，lambda 隔离 pop 明确不丢弃以免误吃外层注释）；`static` 后换行按 vendor 逐字报错（登记 §5.7 (ii)）。
- 测试：✅ `GdScriptParserTest` 新增 15 例（归属/region 边界/dedent 双向/注解交错/丢弃规则/EOF/纯注释体负例等），`GdParserParityTest` 新增 `commentAttributionParity` 15 条。
- 属性块与 range 收尾：✅ 内联属性尾缘取最后 accessor suite 的内容尾（不吃 suite 自己的 DEDENT）；`layoutBlockStart`（多行套件/类体块起点回填行尾空白，与旧路径块起点一致）；`parseLambdaLike` 保存恢复 `statementStartByte`、`parseParameter` 本地化起点（lambda 体/签名嵌套不再覆盖外层 range 起点，两处均以 fixtures 实证并加回归）；`parseAssignment` 右缘补接 `extentEnd()`。
- **验收：✅ 13 个 fixtures 全文件双路径 parity 通过（含 `Sample10_Operators.gd`，由 `GdParserParityTest.fixtureFullFileParity` 动态发现断言）；lambda/match/属性块/region 专测全绿；18 个前端测试类回归全绿（`GdScriptParserTest` 239 例中的两类合计 + parity 动态用例）。**
- 审阅修复（review-expert-a/c 复核确认）：
  - `advance()` 按 vendor 逐字清 `lambdaEnded`（`gdscript_parser.cpp:582`）——修复 lambda 实参后 `return` 值被吞（`if f(func(): return 1, 2):\n\t\treturn 3` 实证）。
  - `parseStatement` 表达式失败分岔补 vendor `in_lambda` 分支（`gdscript_parser.cpp:2163-2175`）：lambda 体内不能开头的 token 置 `lambdaEnded` 而不消费，外层表达式分隔符保留（`g(func():\n\treturn 1\n\t, 2)` 实证两实参零诊断）。
  - match 缺 INDENT 按 vendor 立即返回：不消费 DEDENT（不再吃掉外层套件边界）、不做注释丢弃。
  - match 尾部注释改为按缩进 scoped 丢弃（仅 ≥ match 体缩进的分支层注释丢弃），col-0 外层注释不再被误吞；分支间 col-0 注释行为登记 §5.7 (ii)（旧路径 grammar 报错）。
  - match 悬空 `@warning_ignore` 按 vendor 报 `Annotation "@%s" does not precede a valid target, so it will have no effect.`（仅 `@warning_ignore` 入栈，`@export` 等仍走 "not allowed" 且不入栈）。
  - 分组内注释丢弃改为 **span 记录**（`groupingCommentSpans` + 各排水点跳过），修复游标前缀阻塞导致分组内注释外泄（`# before\n@export_range(\n\t0, # internal\n\t10\n)` 实证：序列注释保留、组内注释忽略）。
  - `parseParameter` 右缘补 `extentEnd()`（多行 lambda 默认值时参数 range 不再跨入 DEDENT）。
  - `parse()` 末尾加 multiline 双栈配平断言（vendor debug 不变量）。
  - A 提出的 for 类型失败后 `in` 消息选择问题经 vendor 核实**不成立**（`parse_type` 失败返回 `nullptr`，`gdscript_parser.cpp:3860-3861`，消息选择与现状一致），已加钉死测试。
  - C 提出的多重 dedent 混合缩进注释组归属差异经 4 组探针实证旧路径确为上游承认的启发式行为，按 §5.4 登记为已知 parity 差异（主流单层形态两路径一致）。
- 审阅修复·第二轮（A/C 复核确认）：
  - `parsePrecedence` 中缀循环头补 vendor `lambda_ended` 早退（`gdscript_parser.cpp:2807-2809`）：lambda 边界后的同缩进运算符不再被折叠（`return func(): return 1\n\t+ 2` 中返回值为裸 lambda、`+ 2` 为独立一元语句；旧路径与 vendor 一致，已入 parity）。A 原始复现（`return 1 +\n\t, 2`）经探针证实两路径均为非法源（旧路径产出畸形 `+ ,` 运算符 + ERROR），不构成 parity 缺陷，但触发的合法形态缺失属实。
  - lambda 体前的 pending 注释处理：采用**延迟-归还**机制（`deferCommentsBefore`/`restoreDeferredComments`，可变 trivia 视图 `GdTokenizer.modifiableComments`）——`func` 关键字前的注释在体解析期间移出游标（既不属 lambda body，也不被体排水窃取），体毕归还游标头；组内注释随后由分组闭合 span 惰性跳过，序列注释回到外层排水（`# before\n@custom(\n\t# internal\n\tfunc():…` 与嵌套 lambda `@custom(func(): return 2)` 两复现实证，后者与旧路径逐字节 parity 并入语料）；同模式隔离 `pendingAnnotations`（try/finally save/clear/restore，与 accessor 套件隔离同构），外层悬挂注解不再被 lambda body 窃取（`@first\n@custom(func(): return 2)` 直接与默认参数嵌套两复现，均入 parity 语料）。
  - match 尾部 scoped 丢弃注释同步扩展 match 右缘（旧路径 match extent 覆盖被丢注释；`_: pass\n\t\t# branch` 实证并入 parity）。
  - §5.4 混合缩进注释组差异边界由"多重 dedent"扩展为"dedent 尾窗口（含单层）"，附最小单层样例（C 复核裁决）。

### Phase 5：错误恢复与诊断（P0）

实施状态（2026-10-07 完成）：

- 核心机制在前序阶段已落地，本阶段核实并补齐语料：`synchronize()`（§2.2 同步集、停在同步 token 前不消费、`IF` 刻意排除）、suite 空语句熔断（`errorCount++ > 100`，第 102 次触发 `Too many statement errors.`，仅统计 null 语句）、所有恢复循环的进度保证（逐循环核实：继续条件必消费逗号/关键字或触达 EOF/DEDENT，无原地重试路径）、链式赋值诊断（`parseAssignment` 的 `!canAssign` 分支，vendor `gdscript_parser.cpp:3128-3132`）。
- 新建 `GdScriptParserErrorRecoveryTest`（26 例，内联语料，类级 `@Timeout(10s)` 终止保护 + 统一 `recoverableParse` 门禁断言 ≥1 条 ERROR 且诊断 range 不越界）：synchronize 兄弟恢复（类体/套件两层）、坏表达式（缺失初始化器、悬挂运算符）、EOF 未闭合构造（分组/数组/字典/调用/字符串）、EOF 悬挂点（`node.` 属性点号、裸函数头）、赋值语境族（语句级链式赋值、调用实参、变量初始化器——均锚定 vendor `p_can_assign == false` 恢复形状：丢弃被赋操作数、外层赋值/实参列表保留）、MISSING 类（缺冒号/缺 INDENT/缺右括号）、lambda 参数未闭合、混合缩进报告后继续。
- 人工抽查 5 个代表性输入与 Godot 对照（无编辑器二进制，改以 vendor 源码追踪为基准，commit c016f22；行列号按 Godot 字符列口径），结果以注释记录在用例上：链式赋值（1 错，`b` 操作数被丢弃，存活 AST 为 `a = 1`）、EOF 未闭合调用（1 错，range 落于 `previous` token）、类声明缺冒号（1 错，consume 失败不中止 `parse_class`）、match 缺 INDENT（1 错，早退不吃外层 DEDENT）、`func f():\n\tnode.`（1 错，函数与降级 `node` 表达式语句存活）。
- 排查实证一处 vendor 一致行为并钉死：`func f(a, b:` 中 `:` 被 `parseNameAndType` 消费为类型注解冒号（vendor `parse_parameter` → `parse_type` 同构），导致缺 `)` 诊断后走 bodyless 函数路径、类体 synchronize 吞掉后续 `pass`（vendor `gdscript_parser.cpp:1536-1544, 1834-1840`）。
- §5.6 暂缓检查反向钉住：`f(1)(2)` 与 `1 + 2 = 3` 不产生"Cannot call on an expression"/赋值目标类型诊断（留分析层）。
- 审阅修复（review-expert-c 一轮，复核确认后收尾）：
  - **BLOCKING**：深层未闭合构造击穿 JVM 栈（`"(".repeat(4096)` 沿分组递归、`"func(a = ".repeat(1024)` 沿 lambda 默认参数递归，`StackOverflowError` 先于任何诊断抛出，违反 §5.7 "不抛异常"）。修复：新增统一嵌套深度预算 `MAX_NESTING_DEPTH = 256`（共享计数器 `nestingDepth` + `enterNestingLevel()`/`exitNestingLevel()` try/finally），接入全部四个输入驱动递归入口——`parsePrecedence`（覆盖分组/容器/调用/lambda 默认参数/一元三元嵌套）、`parseSuite`（覆盖控制流套件递归）、`parseMatchPattern`（容器 pattern 递归）、`parseNestedClass`（嵌套类递归）；预算耗尽报 `Maximum nesting depth exceeded.` 并按构造返回 null/空 Block/占位 ClassDeclaration，剩余 token 由外层迭代式恢复线性消费。Godot 无对应限制（C++ 栈容忍更深），此为 JVM 安全裁剪的刻意偏差。五条深度语料（4096 括号、1024 lambda 默认、600 嵌套套件、512 容器 pattern、600 嵌套类）+ 正反边界（64 层合法、`parse()` 间状态复位）。
  - **MEDIUM**：`recoverableParse` 的 range 门禁误用 UTF-16 `String.length()` 作上界；按 §5.1 改为 UTF-8 字节长度，并补中文/emoji 错误语料（实证 `Point.column` 为字节列）。
  - **LOW**：两处 EOF 对照注释把 vendor 合成坐标误记为物理终点（vendor tokenizer 在 EOF 合成换行并把末 token 终点延至下一行 1 列，`gdscript_tokenizer.cpp:320-331, 356-361, 665-667`）；注释更正为"vendor 原始坐标 vs 本项目物理字节归一化"的区分，起点断言不变。
  - **BLOCKING（二轮）**：`parseTypeInternal` 容器类型递归未接入预算（`Array[`×8192 击穿，`Nested typed collections are not supported.` 的拒绝发生在递归返回之后）。修复：入口守卫同一预算（拆出 `parseTypeInternalImpl`）。
  - **HIGH（二轮）**：预算耗尽状态不外传——深度计数器随帧展开递减即重新放行，导致 lambda 默认参数链在预算边界处（实证 256/257 层）由外层 lambda body 套件吸收后续顶层声明。修复：新增粘滞标志 `nestingBudgetExceeded`（跳闸时置位，`synchronize()` 抵达语句边界时清除）；标志置位期间 `parseLambdaLike` 不开 body 套件（占位空 Block）、`parseStatement` 直接 bail 到 synchronize。新增 255/256/257 三层级边界钉死：`after` 始终为顶层兄弟声明，255 层零错误、≥256 层报深度诊断。
  - **HIGH（三轮）**：跳闸区域的**布局 token 泄漏**——跳闸于非套件点（如 if 条件）时，后续更深层行的 INDENT 被当垃圾逐行消费、而对应 DEDENT 在 dedent 到 0 时成堆出现，超出已解析套件帧数量的"孤儿 DEDENT"使顶层类体提前结束（实证 400 层 if + accessor 场景丢失后续顶层成员）。修复：跳闸时记录 `poisonedLineIndent`（跳闸行缩进，`lineIndentOf` 按字节列扫描行首空白）；`synchronize()` 在跳闸期间切换为布局感知消耗——垃圾 INDENT/DEDENT 配对计数（`garbageIndents`）、NEWLINE 直接吃掉、行首 token 缩进 ≤ 跳闸行时停在边界前，DEDENT 超出配对余额时停住留给已解析套件帧。`parseMatch` 循环头补跳闸检查（match 层无 parseStatement 尾部钩子）。配套边界语料：lambda 套件 127/128、accessor 100/400、match pattern 253/254、match 深层注解、多行调用包裹 lambda 链——均断言顶层兄弟存活。
  - **BLOCKING×2 + WARNING（四轮）**：(a) match 循环头 `synchronize()` 后必须 `continue` 重查 DEDENT/EOF 守卫，否则把已结束的 match 布局边界当新分支解析（多行 body 变体实证）；(b) 垃圾 DEDENT 配平消费后 `previous` 为 DEDENT，行首门禁只认 NEWLINE 会漏判——扩展为 NEWLINE/INDENT/DEDENT 任一布局 token 后置皆视为行首（实证同级 `inner_after` 被吞）；(c) 缩进计量统一为 lexer 布局宽度（tab=4/space=1，`GdTokenizer.TAB_SIZE` 转为包可见共享），门禁改用 `lineIndentOf` 而非字节列 `indentOf`（实证等宽混合缩进被误判为更深缩进）。三轮复现全部转为回归语料；另实证钉死：跳闸分支的多行 body 行属中毒区域被一并消费（与 vendor 缺冒号恢复的最小损害等价）。
  - **BLOCKING + WARNING（五轮）**：(a) `synchronize()` 只在入口清 `panicMode`，而中毒区域扫描时 `advance()` 内的 tokenizer 错误会重新置位——出口（同步 token 提前返回与共享出口）补清，否则外层语句尾部钩子二次同步吞掉外层 DEDENT（实证 `pass ]` 变体中 `after` 被错误吸入函数体）；(b) match 尾部清理原只收 `wholeLine()` 注释，行尾注释会阻断清理并连同后续整行注释一起泄漏到外层函数体——条件放宽为"行尾注释（必属 match 内部行）或缩进足够的整行注释"，外层 `# outer` 仍正确保留 pending。两复现均已入语料并断言作用域归属。
  - **已知限制（五轮 BLOCKING，决策：登记不修）**：**跨行分组的 continuation 行跳闸**会破坏作用域归属。复现：`func f():\n\tmatch x:\n\t\t[\n\t\t\t` + `"[".repeat(254)` + `1` + `"]".repeat(254)` + `\n\t\t]:\n\t\t\tpass\n\t\t_: return 7\n\tvar local_after = 7\nvar after = 2\n` —— 实际：顶层 `[f, local_after]`（`after` 丢失），fallback 的 `return 7` 脱离 match。成因链：(1) 外层 `[` 的 `pushMultiline` 丢弃了 pending 的 `INDENT(8→12)`，但 tokenizer 缩进栈已记录 12；(2) 跳闸后 pattern 帧在失败路径上先 popMultiline（恢复布局扫描）再消费分组剩余内容，导致 continuation 行的 `DEDENT(12→8)` 成为无 parser 帧认领的孤儿布局；(3) `garbageIndents` 配对计数无法识别该孤儿（对应 INDENT 已被丢弃）；(4) `poisonedLineIndent` 取跳闸 token 所在行（continuation 行，12）而非逻辑构造起点行（分支行，8），闭合行 `]:`（8 ≤ 12）被误判为恢复边界。已尝试并**回退**的方案：`popMultiline` 时配平跳过分组剩余内容——跳过与展开帧自身的 consume 互抢 closer，反而让部分 pattern "成功"解析成巨型嵌套并吞掉后续分支。**决策依据**：vendor 无深度限制故无此路径（C++ 栈容忍任意深度），该缺陷只在 >256 层病态嵌套与跨行分组同时出现时影响恢复质量——解析仍终止、仍产生有效诊断、不抛异常，满足 §5.7 的最低恢复契约；正确修复需 tokenizer 暴露缩进栈深度 + parser 跟踪布局作用域数来识别孤儿 DEDENT，复杂度与风险不成比例。回归护栏仅钉终止性与诊断存在性（`multilinePoisonedPatternTerminatesWithDiagnostics`），不钉作用域归属。

任务：

- `synchronize()` 接入点已在 Phase 2 接线，本阶段补齐其错误语料覆盖；部分 AST/占位政策逐构造落地（§5.2）。
- 诊断消息对齐（§5.6）；suite 空语句计数上限（§2.2 准确语义）与进度保证（所有恢复循环必须消费 token 或触达 EOF/DEDENT 守卫）。
- EOF/缩进/未闭合构造场景；`MISSING` 类诊断（期望 token 缺失）。
- 新建错误语料集（内联字符串用例为主）：覆盖坏语句后兄弟恢复、坏表达式、未闭合套件、EOF 悬挂点（对照现有 `func f():\n\tnode.` 用例）、链式赋值 `a = b = 1`（Godot 报 `Assignment is not allowed inside an expression.`）、调用参数/初始化器中的赋值。

验收标准：

- `GdScriptParserErrorRecoveryTest` 通过：每个用例断言可终止（测试超时保护）、≥1 条有效 ERROR 诊断、兄弟结构恢复正确。
- 人工抽查 3-5 个代表性错误输入，与 Godot 编辑器诊断数量/位置对比并记录结果于测试注释。
- 运行：`script/run-gradle-targeted-tests.sh --tests GdScriptParserErrorRecoveryTest`。

### Phase 6：Facade、并发与收尾（P1）

任务：

- `GdScriptParserFacade`（`parse`/`parseStrict`/`tokenize`，§3.1 raw 流语义）；共享 facade 并发 parse 测试。
- 更新 `doc/architecture-overview.md`（双数据流图、包地图、错误模型、parity 规则索引）；在 tree-sitter 计划文档中交叉引用本文档。
- 全量回归：`./gradlew clean build --no-daemon --info --console=plain`。

验收标准：

- 并发测试与全量构建全绿；文档更新完成。
- 运行：`script/run-gradle-targeted-tests.sh --tests GdScriptParserFacadeTest` + 全量 build。

## 7. 测试策略与验收细则

- 分层：`GdTokenizerTest`（token 流快照）→ `GdScriptParserTest`（合法语法 AST）→ `GdScriptParserErrorRecoveryTest`（容错）→ `GdParserParityTest`（双路径一致）→ `GdScriptParserFacadeTest`（入口/并发）。
- parity 实现：复用 `AstSexprSerializer` 输出做逐字符 diff；fixture 遍历模仿 `CstToAstMapperTest` 的动态 `.gd` 发现模式；诊断列表不参与合法语料 parity（非法语料与 §5.7 (ii) 登记项按其条款断言）。
- 新增语料优先内联字符串；确需文件时放 `src/test/resources/gdscript` 并纳入动态遍历。
- 迭代期只跑目标测试类（AGENTS.md 约定）；每 Phase 收尾跑一次既有前端测试回归（`CstToAstMapperTest`、`AstSexprSerdeTest` 等）确认 tree-sitter 路径零影响；PR 前全量 `clean build`。
- 每个 Phase 的验收须同时满足：该 Phase 测试全绿 + 既有测试无回归。

## 8. 风险登记册

1. **D3 优先级分歧（已确认与 Godot 锚定）**：Godot 与固定版 tree-sitter grammar 对 `**`/一元/`as` 的组合结构不同（§5.8）；fixtures 审计确认不受影响，通用语料按登记分歧管理。残余风险：未来新增语料若无意引入分歧组合会被 parity 测试拦截，须按 §5.7 (iii) 登记。风险等级：低。
2. **Unicode/列换算**：Godot 列按字符、tree-sitter 列按字节；统一经 `SourceTextIndex` 换算并加非 ASCII parity 用例（中文注释/字符串/标识符）。风险等级：中。
3. **EOF 合成坐标**：Godot 在 EOF 合成 `NEWLINE` 时行号 +1，token end 行列可能落在非物理行 → `Range` 一律以物理字节游标换算，合成 token 锚定物理 EOF；Phase 0/1 已排对应测试。风险等级：中。
4. **注释归属**：插入规则已用现有路径 dump 钉死（§5.4）；表达式内部注释等未发现形态在 Phase 4 parity 中暴露并登记。风险等级：低。
5. **恢复死循环**：所有恢复/同步循环必须有进度保证与 suite 空语句计数上限；错误语料测试加超时。风险等级：中。
6. **Godot master 漂移**：基线锁定 commit `c016f22`；升级走独立评估。风险等级：低。
7. **pattern 形状适配**：match pattern 在现有 AST 中以 `Expression` 表达（`PatternBindingExpression` 等），Godot `PatternNode` 形态差异大；key-only 字典 pattern 已登记信息丢失（§5.2），其余 Phase 4 以 tree-sitter 路径产出为准逐一钉死。风险等级：中。
8. **parity 过度承诺**：非法源与 §5.7 (ii) 类语法不做 AST 一致承诺，避免被 tree-sitter 容错形状绑架。风险等级：低。
9. **性能**：递归下降 + 单 token 前瞻成本低；v1 不设阈值，仅在 Phase 6 记录 fixtures 解析耗时基线。风险等级：低。

## 9. 参考资料

- Godot 源码 vendor 副本（commit `c016f22009aa773f0b2e6fce72baec168746491f`）：`vendor/godot/gdscript_tokenizer.h`、`vendor/godot/gdscript_tokenizer.cpp`、`vendor/godot/gdscript_parser.h`、`vendor/godot/gdscript_parser.cpp`（上游路径：`modules/gdscript/`）
- 仓库文档：`doc/architecture-overview.md`、`doc/tree-sitter-ng-gdscript-implementation-plan.md`
- tree-sitter-gdscript：https://github.com/PrestonKnopp/tree-sitter-gdscript
