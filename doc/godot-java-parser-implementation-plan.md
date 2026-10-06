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
- `#region <label>`/`#endregion` 注释分类为 `RegionDirectiveStatement("start"/"end", label)`（`label` 为关键字后文本；`#endregion` 的 label 为 `null`，已钉死）。
- Phase 4 对 fixtures 全量注释归属做 parity 验证；若发现无法以该规则复现的位置（如表达式内部的注释），登记为已知 parity 差异并附样例。

### 5.5 负号吸收（偏差 D1）

- Godot tokenizer 在一元位置将 `+`/`-` 吸收进数字字面量（使 `-2 ** 2` 解析为 `(-2) ** 2`）；本项目 AST 契约将符号建模为 `UnaryExpression("-", operand)`（现有 serde 测试已钉死该形状）。
- 决策：**不吸收**，`-`/`+` 始终作为独立 token，parser 按 `PREC_SIGN` 解析一元运算。已钉死：现有路径对 `-1`、`-2` 等一律产出 `UnaryExpression`，普通一元场景两路径一致。
- 不吸收时 `-2 ** 2` 在 Godot 优先级下解析为 `-(2 ** 2)`（一元操作数只按 `PREC_SIGN` 解析，`PREC_POWER` 更高），而现有路径产出 `(-2) ** 2`——该分歧并入 D3 优先级分歧集（§5.8），不再单列。

### 5.6 诊断对齐政策

- 语法错误消息语义对齐 Godot（可精简 `vformat` 细节）；3.x 移除关键字给出迁移提示，与 mapper 现有消息方向一致。
- Godot 在 parse 阶段的「语义型」检查（重复参数名/重复成员名、静态函数中 `self`、构造器返回值、循环外 `break`/`continue`、默认参数顺序、pattern bind 冲突等）：首版**仅移植无歧义的纯语法判定**（重复参数名、默认参数顺序、`break`/`continue` 循环上下文、构造器 `return` 带值）；依赖符号表/类型的一类一律裁剪。每条移植的检查须配单测。
- tokenizer 错误（非法字符、未闭合字符串、缩进混用等）进入诊断列表，severity 为 ERROR，不中断扫描。

### 5.7 parity 范围定义

parity 语料分三类管理（非法源不适用 parity，另有独立验收规则）：

- **(i) 共同支持且结构一致的语法**：双路径 AST 经 `AstSexprSerializer` 序列化后**逐字符一致**（含全部 range）。13 个 fixtures 均属此类（现状：全部零诊断、零 `Unknown*` 节点，由 `CstToAstMapperTest` 断言；Phase 0 审计确认不含 §5.8 分歧组合）。
- **(ii) Godot 合法但旧路径报错/丢失的语法**：不作 AST parity 要求；新 parser 按 Godot 产出，或登记信息丢失。已实证登记：
  - `$%Child`、`$Root/%Child`（`%` 位于 `$` 后或 `/` 后）：Godot 合法，旧路径报 ERROR——新 parser 按 Godot 产出 `GetNodeExpression`。
  - key-only 字典 pattern（`{"name"}:`）：旧路径丢弃 key + WARNING——v1 对齐该行为并登记为 AST 表达力缺口（§5.2）。
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

### Phase 5：错误恢复与诊断（P0）

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
