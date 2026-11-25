# 纯 Java GDScript Parser 实施文档（Godot 官方 Parser 移植版）

本文档记录纯 Java GDScript parser（`frontend.gdscript`，Godot 官方 tokenizer/parser 的移植版）的现行架构、AST 契约、容错机制、测试策略与已知限制。面向调用方的版本变化见[架构总览的 0.8.0 迁移指南](architecture-overview.md#4-migrating-to-080)。

## 1. 定位与现状

### 1.1 定位与动机

- 自 0.8.0 起，纯 Java 移植版是唯一解析入口。它不依赖原生库，直接产出 `frontend.ast` 的 AST 和诊断，不经过 CST 或 lowering。
- tokenizer 显式产生 `NEWLINE`/`INDENT`/`DEDENT`，按 Godot 的 pending 与 multiline 规则处理换行和缩进；parser 使用 panic-mode 与同步集恢复，能在出错时提供诊断和部分 AST。
- Godot 移植范围严格限定为 parser：裁剪语义分析（类型解析、常量折叠、符号表、Godot 补全钩子、注解验证等），产出 AST 与语法诊断；纯 Java 路径另提供基于容错 AST 与 token 的补全上下文分类，不移植 Godot 的语义补全状态机（§12）。

### 1.2 当前状态

- 移植已完成：tokenizer、Pratt 表达式、全部语句/声明、panic-mode 恢复、注释/region trivia、Facade（`parse`/`parseStrict`/`tokenize`/`parseCompletionContext`）与并发契约全部落地。
- 移植阶段的一次测量（不是 0.8.0 重测结果）：JIT 预热后，13 个 fixtures（约 41.6 KB）全量轮均值约 4.38 ms，吞吐量约 9.5 MB/s。环境：JDK 25.0.4、Xeon E5-2699 v4 @ 2.20GHz。该结果仅作量级参照，不设回归阈值。
- 测试分层见 §7；已知限制与风险见 §11。

## 2. Godot 参考基线

- 仓库：`godotengine/godot`，commit `c016f22009aa773f0b2e6fce72baec168746491f`（记录于 `vendor/godot/COMMIT_SHA`）。
- 参考文件已 vendor 进仓库，实施/维护时直接对照（仅作对照参考，不参与编译）：
  - `vendor/godot/gdscript_tokenizer.h` / `vendor/godot/gdscript_tokenizer.cpp`
  - `vendor/godot/gdscript_parser.h` / `vendor/godot/gdscript_parser.cpp`
- 移植代码与 vendor 源文件的对应关系以函数级 `///` 注释标注（如 `/// Port of GDScriptTokenizerText::check_indent`），保证双向可追溯；注释须自解释，不引用本文档章节编号。
- 升级 Godot 基线时须同步更新 vendor 副本与 `COMMIT_SHA`，并走独立的评估流程。
- 许可：Godot 为 MIT License。若未来直接复制其代码片段进发布产物，须附带 Godot 版权与许可声明。

## 3. AST、诊断与序列化边界

- `frontend.ast` 解析器无关：sealed `Node`/`Statement`/`Expression` + record 节点；`Range` 为 UTF-8 字节偏移 + 0-based `Point(row, column)`（列为字节列）；集合组件构造时 `List.copyOf` 冻结。
- parser 直接调用 AST record 的构造器。`ErrorStatement` 和 `ErrorExpression` 的 `kind` 仍为 `frontend.cst.CstIssueKind`（`ERROR`/`MISSING`），这是无原生依赖的兼容枚举。parser 在受损表达式槽位构造 `ErrorExpression`，在缺名的点号链上构造 `MissingAttributeStep`（§9）；`ErrorStatement` 和 `Unknown*` 仍属于 AST 与序列化契约。
- `LiteralExpression.kind` 的字符串集合是 AST 契约词汇表（§5.3）。
- parser 消费属性 get/set 语法，但没有独立的 accessor AST 节点。受损 accessor 上未附着的注解可保留在 `VariableDeclaration.recoveryAnnotations()` 中。
- 注释不进入 token 流，作为 trivia 收集，再按归属生成 `CommentStatement` 或 `RegionDirectiveStatement`（§5.4）。
- `AstMappingResult` 持有 `SourceFile` 和诊断列表。`AstDiagnostic` 有 severity、message、nodeType、range，以及可空的 `code` 和 `recovery`；四参数兼容构造器不填后两项。
- `frontend.serialize` 按 AST record 组件反射读写 S-expression。序列化对象是 AST，不是诊断列表或同代 `RecoveryLink`。

## 4. 架构与移植约定

### 4.1 包结构与主要类型

包 `dev.superice.gdparser.frontend.gdscript`（`module-info.java` 已导出），主要类型：

- `GdTokenType`：token 类型枚举，逐一对齐 Godot `Token::Type`（`TK_*` 去前缀，省略 `TK_MAX`），含 displayName 对照。
- `GdToken`：record——类型、源文本切片、字面量种类（`GdLiteralKind`）、ERROR 消息载体（对齐 Godot `ERROR` token 的 `literal` 载体）、起止字节偏移与 1-based 行/列（内部对齐 Godot 坐标；对外经 `SourceTextIndex` 换算为 `Range`）。ERROR token 的源切片置空（字节区间精确即可），避免大文件错误密度下 O(n²) 内存。
- `GdTokenizer`：`GDScriptTokenizerText` 的移植，增量 `scan()`；注释 trivia 收集（§5.4）；缩进状态快照 push/pop（对齐 `push_expression_indented_block`，供 lambda 使用；空栈 pop 抛 `IllegalStateException`——Godot 为静默 no-op，此处按内部 bug 处理）。
- `SourceTextIndex`（包内）：UTF-8 字节偏移 ↔ 行列换算、行起始表；以**物理字节游标**为 `Range` 的唯一事实源（0-based 行、字节列）；Godot 在 EOF 合成的 token 行列不参与换算，锚定物理 EOF（§5.1）。
- `GdScriptParser`：递归下降 + Pratt，直接构建 `frontend.ast` 记录；内部持有诊断列表、panic 标志、multiline 栈、lambda 状态、嵌套深度预算（§9）。
- `CompletionContext` / `CompletionContextAnalyzer` / `CompletionTokenStream`：补全 DTO、AST/token 分类与有效 multiline token 视图（§12）。
- `GdScriptParserFacade.parseCompletionContext(String, long) -> CompletionContext`：容错 AST + token 分析入口，光标为 UTF-8 字节偏移，越界自动 clamp；不加载 native，分类与范围合同见 §12。
- `GdScriptParserFacade`：公开入口——`parse(String) -> AstMappingResult`（容错）、`parseStrict(String) -> SourceFile`（遇首个 ERROR 诊断即抛 `IllegalStateException`，消息格式 `Parsing failed at <nodeType>: <message>`；WARNING 不触发）、`tokenize(String) -> List<GdToken>`（**raw 扫描流**：无 parser 的 multiline 干预，可能含上下文相关的缩进诊断与结尾 EOF token，不作为合法源「零 ERROR」的判定依据；parser 实际消费的 token 流以 `parse` 内部为准）。Facade 无状态（每次调用创建独立 parser/tokenizer），共享实例可安全并发。

### 4.2 数据流

```text
GDScript Source (String)
  -> GdScriptParserFacade
  -> GdTokenizer (token 流 + 注释 trivia + tokenizer 诊断)
  -> GdScriptParser (Pratt + panic-mode 恢复)
  -> SourceFile AST + AstDiagnostic list (AstMappingResult)
  -> (可选) AstSexprSerializer / AstSexprDeserializer
```

### 4.3 移植约定

- 遵循 AGENTS.md：record/密封接口、`var`、`List.of`、`List.getFirst/getLast`、switch 表达式、文本块、`///` 文档注释、record 字段不写注释（用类级 `@param`）、`@Nullable`/`@NotNull`、`requireXXX`/`checkXXX` 命名。
- 字符分类用 JDK `Character.isUnicodeIdentifierStart/isUnicodeIdentifierPart` + `_` 近似 Godot XID 表，并显式排除越界哨兵 0（JDK 将 NUL 等 identifier-ignorable 控制符判为标识符字符，不排除会在 EOF 处死循环）。
- 字面量不解码值：`GdLiteralKind` + 源切片承载（对齐 AST 契约）。
- 标识符判定移植 Godot `Token::is_identifier()`（`match`/`when`/`PI`/`TAU`/`INF`/`NAN` 可作名字）与属性名/节点路径名的 `is_node_name()` 集合。
- 未配对 UTF-16 代理项按 JDK UTF-8 替换规则计 1 字节。
- 不修改 build 脚本；`module-info.java` 的 exports 调整属于源码范畴。

## 5. AST 产出契约与差异决策

### 5.1 位置契约

- `Range`：UTF-8 半开字节范围 `[startByte, endByte)`，行和字节列均从 0 开始。
- `Range` 的唯一事实源是**物理字节游标**：tokenizer 内部按 Godot 约定维护 1-based 行/列（列按字符计）仅供诊断消息；产出 AST 前经 `SourceTextIndex` 由字节偏移统一换算；Godot 在 EOF 合成的 token 行列不参与换算，锚定物理 EOF（§8.1 EOF 坐标条款）。换算逻辑有单测覆盖（含 CRLF、中文、emoji、tab、无末尾换行、末尾注释、末尾多行字符串）。

### 5.2 AST 形状适配（Godot AST → 本项目 AST）

以下形状是本项目的 AST 契约，由纯 Java 解析和 serde 回归测试约束：

- `elif` 链：Godot 在 false suite 中嵌套 `IfNode`；本项目折叠为 `IfStatement.elifClauses`（`ElifClause(condition, body)`）及可选 `elseBody`。
- 构造器：名为 `_init` 的 `func` 产出 `ConstructorDeclaration`（无 `name`/`isStatic` 组件），其余函数产出 `FunctionDeclaration`。构造器可显式声明返回类型：无 `->` 时 `returnType = null`，有 `-> T` 时保留对应 `TypeRef` 及源范围。
- 后缀链：Godot 逐节点构造 `SubscriptNode`/`CallNode`；产出时按现有路径形状折叠：`obj.a.b(1).c[2]` → 单个 `AttributeExpression(base, [AttributePropertyStep("a"), AttributeCallStep("b", [1]), AttributeSubscriptStep("c", [2])])`；`arr[0]`（下标直挂 primary）→ `SubscriptExpression(base, [index])`；`f(x)` → `CallExpression(callee, args)`。
- `super`：`super.foo()` → `AttributeExpression(IdentifierExpression("super"), [AttributeCallStep("foo", args)])`；`super()` → `CallExpression(IdentifierExpression("super"), args)`（不引入 `is_super` 类标记）。
- 推断类型标记 `:=`：`var`/`const`/参数的 `COLON`+`EQUAL` 识别为推断标记，`type` 组件为非空 `TypeRef`——`sourceText` 为从 `:` 起至 `=` 止的源切片（仅去首尾空白；紧邻写法为 `":="`，`: =` 形态保留内部空白为 `": ="`），range 覆盖整个标记含内部空白。**不是** `null`。
- `VariableDeclaration.sourceNodeType` 保留现有 AST 和序列化取值：`var` → `"variable_statement"`、`const` → `"const_statement"`。
- `not in`：产出 `BinaryExpression("not in", left, right)`；Godot 的 `NOT` infix 规则在产出层折叠为该形态（与 `is not` 同理）。
- `assert`：语句位置的 `assert(cond, msg)` 产出 `AssertStatement(condition, message)`，不是 `ExpressionStatement`。
- Lua 风格字典 key：`{ name = 1 }` 的 key 产出**合成** `LiteralExpression("string_name", "&\"name\"")`，范围对应源中的标识符；Python 风格 `key: value` 直接保留 key 表达式。
- 类体 `pass`（偏差 D4）：产出 `PassStatement`（Godot 不产出节点）。
- key-only 字典 pattern（如 `{"name"}:`）保留 `DictEntry` 的 key，`value` 为 `null`，不因省略 value 产生诊断。与之不同，`{"name": _}` 的 value 为通配 pattern，`{"name": }` 的空槽产生诊断并保留非空的恢复节点。
- 函数/信号/参数：`FunctionDeclaration`/`SignalStatement`/`Parameter`（含 `variadic` 对应 Godot rest 参数 `...`）。
- 受损表达式槽位（如 `if` 缺条件、`var x =` 缺初始化器、`1 +` 缺右操作数）：宿主构造带 marker 存活——零消费槽位填零宽 `ErrorExpression(CstIssueKind.MISSING, slotName, "", [extentEnd(), extentEnd()))`，有消费的失败填 `ERROR` kind 片段（`nodeType` `"ERROR"`，覆盖实际消费区间 `[slotStart, extentEnd())`），部分节点（如带 marker 右槽的 `BinaryExpression`）原样保留；语句/声明宿主（if/while/for/assert/const/match 头、match guard/pattern、enum 值、elif 分支）随之存活（§9 sealed-outcome 协议）。缺名点号在链上填 `MissingAttributeStep`（点号末端零宽）并继续续折。
- 恢复占位与诊断归属：parser 不构造 `ErrorStatement`，而是丢弃无法承载失败的语句级构造，再同步。缺闭合符时发出零宽 ERROR 诊断和稳定的 `parse.missing_closer.*` code；若有存活归属节点，则同代 `RecoveryLink` 指向最终 AST 中的该节点及其槽位（§9）。链接不跨序列化保留。

### 5.3 字面量 kind 词汇表（AST 契约）

`LiteralExpression.kind` 固定取值集合：`integer`、`float`、`string`、`string_name`、`node_path`、`true`、`false`、`null`。`sourceText` 保留源切片（含引号/前缀）；Lua 风格字典 key 的合成 `string_name` 文本见 §5.2。

内置常量 `PI`/`TAU`/`INF`/`NAN` 不进入该词汇表。Godot 将其折为数值 `LiteralNode`，本项目不做数值折叠，保留 `IdentifierExpression`。

### 5.4 注释与 region 指令（偏差 D2）

- tokenizer 将注释作为 trivia 收集（文本含前导 `#`、范围、是否整行/行尾），不进入 token 流。
- 插入规则：注释按其源位置插入**所属 suite/类体的语句序列**，作为独立 `CommentStatement`；行尾注释位于其所跟随语句**之后**（同级后继语句），整行注释按源顺序占位。示例：`var x = 1 # tail` → 类体语句序列 `[VariableDeclaration, CommentStatement("# tail"), ...]`；函数体内整行注释按源位置排在相邻语句之间。
- `#region <label>`/`#endregion` 注释分类为 `RegionDirectiveStatement("start"/"end", label)`（`label` 为关键字后文本去首尾空白，空则为 `null`；`#endregion` 的 label 恒为 `null`；`#regionfoo` 也算 start、label `"foo"`，`# region` 带空格则为普通注释）。
- 丢弃规则：套件头行尾注释（`func f(): # c`、`if x: # c`、`else: # c` 等冒号后同行注释）不进入任何语句序列；match 体分支层级的整行注释同样被丢弃（分支 suite 内的注释按常规归属）。
- dedent 前尾窗口的整行注释归属：注释缩进 ≥ 所属 body 缩进时留在 body 内（如 `func f():\n\tpass\n\t# inner\nvar x = 1\n` 中 `# inner` 留在 f 体内），否则归属外层序列（如 `func f():\n\tpass\n# hello\nfunc g():...` 中 `# hello` 归属顶层）；body 中间的整行注释只要下一行代码仍属于该 body 即留在 body 内（col 0 亦然）。
- 表达式内部注释（如数组字面量元素间的 `[\n\t1, # one\n\t2\n]` 或调用/注解实参列表内）不产出 AST 节点。分组闭合时记录跨度，各注释收集点跳过跨度内的注释。
- dedent 尾窗口的混合缩进注释组（如 `func f():\n\tpass\n# low\n\t# high\nvar after = 1\n`）归属外层语句序列。在多层 dedent 中同样如此。

### 5.5 负号吸收（偏差 D1）

- Godot tokenizer 在一元位置将 `+`/`-` 吸收进数字字面量（使 `-2 ** 2` 解析为 `(-2) ** 2`）；本项目 AST 契约将符号建模为 `UnaryExpression("-", operand)`（serde 测试已钉死该形状）。
- 决策：**不吸收**，`-`/`+` 始终作为独立 token，parser 按 `PREC_SIGN` 解析一元运算，不移植 `can_precede_bin_op` 的符号吸收分支。
- 不吸收时，`PREC_POWER` 高于 `PREC_SIGN`，所以 `-2 ** 2` 按 `-(2 ** 2)` 构树（§5.8）。

### 5.6 诊断对齐政策

- 语法错误消息语义对齐 Godot（可精简 `vformat` 细节）；在类体等旧语法位置，`export`、`tool`、`onready` 和旧 RPC 修饰符等 Godot 3.x 写法报带迁移提示的错误。
- Godot 在 parse 阶段的「语义型」检查中，仅移植无歧义的纯语法判定：**重复参数名、默认参数顺序、`break`/`continue` 循环上下文、构造器 `return` 带值**；依赖符号表/类型的一类一律裁剪。每条移植的检查都有单测钉死。
- 识别并**暂缓**的 parse 时检查（均属依赖节点类型/上下文的语义型判定，留给分析层；新 parser 产出结构、不报诊断）：赋值目标类型检查（`Only identifier, attribute access, and subscription access can be used as assignment target.`）、调用目标类型检查（`Cannot call on an expression. Use ".call()" if it's a Callable.`，故 `f(1)(2)`、`$Node(x)`、`PI()` 等新 parser 不报错）、隐式 `super()` 函数上下文检查（`Cannot use implicit "super" call outside of a function.`）。暂缓行为有钉死测试，防止未来移植时意外加回。
- tokenizer 错误（非法字符、未闭合字符串、缩进混用等）进入诊断列表，severity 为 ERROR，不中断扫描。

### 5.7 语法边界与容错验收

- `$%Child`、`$Root/%Child` 中的 `%` 表示唯一节点名，产出 `GetNodeExpression`。
- 脚本前缀或类体中的独立文档字符串（如 `"""doc"""\nclass_name X\n`）会被消费，不产出表达式语句；嵌套类缩进体首行的 `extends Base` 会设置类的超类，不作为普通语句。
- match 体中的 `pass` 不产出分支。match 分支层级的 `@warning_ignore(...)` 会被消费但不保留在 AST；`@export` 等不允许的注解报错。分支间的 col-0 整行注释不会阻断解析，该层注释不产出节点；match 体尾部 dedent 后的 col-0 注释归属外层。
- 纯注释套件（如 `func f():\n\t# only\nvar x = 1\n`）不建立缩进，报缺少缩进体。`assert` 超过两个实参也报错。
- 类体或顶层的 `func(): pass` 报缺少函数名；suite 内的语句级 `func` 支持命名或匿名，产出 `FunctionDeclaration`，匿名时名称为 `"<anonymous>"`。
- `static` 和成员不能跨未续接的换行；例如 `static\nvar x = 1` 报 `Expected "func" or "var" after "static".`。
- 典型非法源的回归断言：容错入口可终止、不因语法错误抛异常；保留有效 UTF-8 范围的 ERROR 诊断；同步后能继续解析健康兄弟声明。测试同时断言该语法所需的部分 AST 和恢复标记。

### 5.8 决策 D3：运算符优先级与 Godot 对齐

parser 的优先级顺序以 vendored Godot 的 `PREC_*` 为准，结合 §5.5 的独立符号 token 策略：

| 表达式 | 本项目构树 |
| --- | --- |
| `2 * 3 ** 2` | `2 * (3 ** 2)`，`PREC_POWER` 高于 `PREC_FACTOR` |
| `-x ** 2` | `-(x ** 2)`，`PREC_POWER` 高于 `PREC_SIGN` |
| `-2 ** 2` | `-(2 ** 2)`，tokenizer 不吸收负号 |
| `1 + 2 as int` | `(1 + 2) as int`，`PREC_CAST` 低于加法 |

## 6. 移植范围清单

| 类别 | 内容 | 处置 |
| --- | --- | --- |
| 移植 | token 类型全集、缩进/换行/续行机制、字面量与字符串全形态、Pratt 表达式、全部语句/声明解析、panic-mode + synchronize 恢复（三处接入点）、suite 空语句计数上限、multiline push/pop（类型解析继承调用上下文、不自行开启）、lambda 缩进快照机制 | 行为对齐 Godot |
| 改造 | 注释（Godot 不入 token 流 → 本项目保留 trivia 并分类为 `CommentStatement`/`RegionDirectiveStatement`） | 偏差 D2，§5.4 |
| 改造 | 负号吸收进数字字面量 | 不吸收，偏差 D1，§5.5 |
| 改造 | 类体 `pass` 保留 `PassStatement`（Godot 不产出节点）；`PI`/`TAU`/`INF`/`NAN` 产出 `IdentifierExpression`（不折数值） | AST 契约，§5.2/§5.3 |
| 改造 | `elif`（Godot 嵌套 `IfNode` → AST `ElifClause`）、后缀链（`SubscriptNode`/`CallNode` → `AttributeExpression`+steps/`CallExpression`/`SubscriptExpression`） | AST 形状适配，§5.2 |
| 裁剪 | 语义分析、类型解析、常量折叠（含内置常数数值化）、符号表、Godot 语义耦合补全钩子、注解验证、警告、文档提取、依赖缓存、二进制路径 | 不移植；另实现纯语法补全上下文分类 |
| 保留 | 公开 AST、诊断和 `frontend.serialize`；AST 兼容枚举 `frontend.cst.CstIssueKind` | 不依赖 CST adapter 或原生库 |

裁剪明细（Godot parse 阶段携带但不移植的语义耦合）：

- `GDScriptAnalyzer` 交互、`DataType` 解析、类型约束与 `resolved_type`、`reduced`/`reduced_value` 常量折叠。
- `SuiteNode` 局部符号表、标识符 source 解析、成员索引重复检查（纯语法层面可判定的诊断除外，§5.6）。
- Godot 代码补全状态机与 `make_completion_context` 钩子、编辑器 cursor 跟踪（本项目补全入口独立实现，见 §12）。
- 注解名称/目标/参数个数验证与 `@tool`/`@icon` 等 parser-time 动作（仅解析注解语法）。
- 警告系统、工具构建的文档注释提取、`GDScriptParserRef`/`GDScriptCache` 依赖解析、二进制 tokenizer 路径。

## 7. 测试策略与验收

- 基础分层：`SourceTextIndexTest` 验证坐标，`GdTokenizerTest` 验证 token，`GdScriptParserTest` 验证合法语法和 AST 形状，`GdScriptParserFacadeTest` 验证公开入口与并发。`AstSexprSerdeTest` 验证 AST 序列化往返和兼容形状。
- `GdScriptAstContractTest` 将纯 Java AST 序列化结果与已有 `.sexp` 快照比较（目录 `src/test/resources/gdparser-parity` 沿用历史名称，不运行第二个解析器）。`GdScriptParserCorpusTest` 对 `src/test/resources/gdscript` 整文件夹具检查 LF、CRLF 版本，并覆盖内联语法样例；`GdScriptParserLineContinuationTest` 单独检查续行。
- 容错以 `GdScriptParserErrorRecoveryTest` 和 `GdScriptParserRangeInvariantTest` 验证部分 AST、恢复标记、诊断和范围。代表性行为可追溯到 §2 所列 vendored Godot 源码。Godot 字符列与本库公开的 UTF-8 字节列不可直接比较。
- `GdCompletionContextTest` 覆盖上下文分类、受损链、类型和实参回退、multiline/lambda 帧、CJK/emoji/CRLF、未闭合字符串、截断及共享 facade 并发。
- Linux 迭代期按子系统跑定向测试，例如 `script/run-gradle-targeted-tests.sh --gradle-task :test --tests GdScriptAstContractTest,GdScriptParserCorpusTest,GdScriptParserLineContinuationTest,AstSexprSerdeTest`。PR 前做根库全量验证：`./gradlew :clean :build --no-daemon --info --console=plain`。

## 8. Godot 官方实现机制摘要（维护参考）

### 8.1 Tokenizer（`gdscript_tokenizer.cpp`）

- 增量 `scan()`：每次调用返回一个 token；错误进入 error 队列（消息存于 token 的 `literal` 载体），以 `ERROR` token 优先返回，扫描可继续。
- 关键字为独立 token 类型；`true`/`false`/`null` 扫描为 `LITERAL`；`PI`/`TAU`/`INF`/`NAN` 为独立常量 token。
- 缩进：indent 栈 + `pending_indents` **有符号计数器**（非队列）：增缩进 `++`、减缩进按弹出层数 `--`，`scan()` 每次调用只发一个 `INDENT`/`DEDENT`；不对齐的 dedent 报错后把新宽度压回栈但**不** `++`；tab 按宽度 4 计；行首空格/tab 混用报错但继续；空行与纯注释行不影响缩进层级、不发射 `NEWLINE`；EOF 合成最终 `NEWLINE` 并按层数扣减 `pending_indents`。
- 换行：pending newline 模型——代码后的物理换行调度一个 `NEWLINE`；`multiline_mode`（由 parser 在分组构造中 push/pop）下丢弃 pending newline；**无括号嵌套计数**；反斜杠续行消耗换行且不发射。
- 符号吸收：`+`/`-` 后紧跟数字（不含 `.5` 形态）且前一 token 不在「可作为二元运算符左操作数」集合（`can_precede_bin_op()` 为假）时，符号被吸收进数字字面量——本项目按 D1 不吸收（§5.5）。
- EOF 坐标：`_advance()` 在消费最后一个字符时立即执行 `newline(true)`（行号 +1、列归 1），此后产生的合成 token（`DEDENT`/EOF）以及 EOF 前最后一个真实 token 的 end 行列落在**不存在的物理行**上——`Range` 以物理字节游标为准换算（§5.1）。
- 注释：`#` 注释不进入 token 流（tools 构建另存 comment 表但不进 AST）；本项目按 D2 以 trivia 形式保留（§5.4）。
- 字面量：十/十六/二进制数字（下划线、指数、前导 `.` 浮点）；单/双/三引号字符串，`r`/`&`（StringName）/`^`（NodePath）前缀（仅引号形态，`&name`/`^path` 裸标识形态在本基线中为解析错误），转义与 `\u`/`\U`；`@` 注解 token。
- `:=` 无独立 token（`COLON` + `EQUAL`）；`->`、`..`、`...`、`**`、`<<`/`>>`、`&&`/`||` 与 `and`/`or` 各有对应 token。

### 8.2 Parser（`gdscript_parser.cpp`）

- 递归下降 + Pratt 表达式解析：`parse_precedence()` 循环 + `get_rule()` 规则表 + `PREC_*` 优先级枚举；二元运算符（含 `**`）左结合（右操作数以 precedence+1 解析）；赋值仅在允许的上下文解析，其 RHS 以禁止赋值的上下文解析（`a = b = 1` 报 `Assignment is not allowed inside an expression.`）；无 walrus 表达式。
- `parse_program`：脚本级前缀（注解、独立文档字符串、`class_name`、`extends`，允许同行 `class_name X extends Y`）后进入 `parse_class_body`。
- `parse_class_body`：成员分派（`var`/`const`/`func`/`signal`/`class`/`enum`/`static` 前缀/类级注解/`pass`/文档字符串）；Godot 3.x 移除关键字报带迁移提示的错误。
- `parse_suite`：冒号后若有 `NEWLINE` 则要求 `INDENT`、解析语句直到 `DEDENT`；否则解析单行套件（分号可串多个语句）；返回空的失败语句被跳过，**仅统计当前 suite 内 `parse_statement` 返回空的次数**——`error_count++ > 100`（即第 102 次）时发 `Too many statement errors.` 并结束**当前 suite**（不终止整个 parse；`push_error` 本身不计数）。
- 语句结束：`end_statement()` 循环消费连续的 `NEWLINE`/`SEMICOLON` 直到非结束符（EOF 停住不消费）；`lambda_ended`/`in_lambda` 状态也算结束。
- 错误恢复：`panic_mode` 标志 + `synchronize()`——重置 panic 后前进至换行/分号/EOF 或疑似语句/成员起始 token（`class`/`func`/`static`/`var`/`const`/`signal`/`for`/`while`/`match`/`return`/注解；`if` 故意排除，因其可出现在表达式中），停在同步 token 之前不消费；接入点为 `parse_program` 前缀、`parse_class_body` 循环、`parse_statement` 末尾三处；`push_error` 累积错误列表；失败构造按构造返回 `nullptr`/部分节点/原操作数；recovery suite 占位。
- 表达式形态：`elif` 表示为 false suite 内嵌套 `IfNode`；`.`/`[`/`(` 后缀链由 Pratt 循环逐个构造 `SubscriptNode`/`CallNode`；`as`/`is`/`await`/三元 `v if c else e`/`$`与`%` 节点路径/`preload` 各有专门入口；`a not in b` 由 `NOT` 的 infix 规则（`PREC_CONTENT_TEST`）解析；`PI`/`TAU`/`INF`/`NAN` 经 `parse_builtin_constant` 折成数值 `LiteralNode`。
- lambda：multiline 上下文中进入 lambda 体前调用 tokenizer 的 `push_expression_indented_block()`（缩进状态快照），体结束后用原始 `scan()` 跳过多余的 `DEDENT`/`INDENT`/`NEWLINE`（不改变 `previous`）再 `pop`。
- 类型解析：`parse_type()` **不自行 push/pop multiline，继承调用上下文**——成员/局部变量类型与函数返回类型（在签名 `pop_multiline()` 之后解析）不能因类型括号跨行；函数/信号参数类型处于签名 multiline 作用域内，允许跨行。
- 类体 `pass`：Godot 不产出任何节点（仅消费 token）；本项目 AST 保留 `PassStatement`（偏差 D4，§5.2）。

## 9. 错误恢复与容错保证（as-built）

- panic-mode + `synchronize()`：同步集与 Godot 逐 token 一致（`if` 刻意排除），停在同步 token 之前不消费；接入点三处：program 前缀、类体循环、语句末尾（match 循环头另有跳闸检查）。
- suite 空语句熔断：仅统计当前 suite 内 `parseStatement` 返回 null 的核心语句次数，`errorCount++ > 100`（第 102 次）报 `Too many statement errors.` 并结束当前 suite，不终止整个 parse。
- 进度保证：所有恢复/同步循环必须消费 token 或触达 EOF/DEDENT 守卫，无原地重试路径；错误语料测试带超时保护。
- 嵌套深度预算（JVM 安全裁剪，Godot 无对应限制——C++ 栈容忍更深，此为刻意偏差）：`MAX_NESTING_DEPTH = 256`，共享计数器接入全部输入驱动递归入口（`parsePrecedence`、`parseSuite`、`parseMatchPattern`、`parseNestedClass`、`parseTypeInternal`）；耗尽报 `Maximum nesting depth exceeded.`，按构造返回 null/空 Block/占位 ClassDeclaration，剩余 token 由外层迭代式恢复线性消费。粘滞标志 `nestingBudgetExceeded` 在跳闸时置位、`synchronize()` 抵达语句边界时清除；置位期间 lambda 不开 body 套件、`parseStatement` 直接 bail 到 synchronize，防止预算边界处状态重入。
- 中毒区域布局感知消耗：跳闸时记录跳闸行缩进 `poisonedLineIndent`（lexer 布局宽度口径，tab=4/space=1）；`synchronize()` 在跳闸期间配对消费垃圾 INDENT/DEDENT（`garbageIndents` 计数）、吃掉 NEWLINE，行首 token 缩进 ≤ 跳闸行时停在边界前，超出配对余额的 DEDENT 停住留给已解析套件帧。
- tokenizer 错误必入诊断：`pushMultiline` 待丢弃 token 走 `scanSkippingErrors` 上报——vendor 裸 `scan()` 会静默丢弃 ERROR token，与「tokenizer 错误必入诊断」政策冲突，此为有意偏差。
- 非法源验收契约（§5.7）：不因语法错误抛异常、可终止；至少一条 ERROR 诊断且 range 不越界（UTF-8 字节口径）；synchronize 后能继续解析健康兄弟声明。
- sealed-outcome 恢复协议：表达式和类型解析返回 `ParseOutcome<T>`。`Ok` 携带健康值，`EnclosedDamage` 携带完整结构及子损伤，`Damaged` 携带部分值，三者均保留已建节点。`Missing` 零消费且未报告，由槽位宿主补发上下文诊断；`Failed` 无值且已报告，不重复补发。表达式槽可用零宽 MISSING 或 ERROR 片段承载无值结果。Pratt 和后缀链在原有边界允许时对三种有值结果继续折叠；完整的数组、字典、调用、下标吸收子损伤而不抹去，闭合分组则透明继承内层结果。缺名点号的 `MissingAttributeStep` 仍阻止调用或下标折到前一属性。语句分派保留有值结果为 `ExpressionStatement`，由 `finishStatement` 结算剩余尾部。
- 类型恢复在内部保留容器标识与 `TypeRef`，公开类型和 serde 不变。非空坏类型保留实际消费的文本与损伤，包括缺名点号、坏闭合及消费 token 时结算的词法错误。`as`/`is` 在有可用类型时保留普通中缀节点，无类型时保留左操作数与恢复 marker；括号诊断沿用表达式宿主，也可由无宿主时建立的 `type.argument` 归属中缀节点。`for` 在类型非 `Ok` 时只在物理同行跳过垃圾，但可匹配通过显式续行直接可见的同一逻辑头 `in`。
- `DiagnosticCodes` 集中登记稳定的诊断代码和允许的 `(code, ownerType, facet)` 组合，例如 `parse.missing_closer.array`、`parse.missing_expression.binary_right`、`parse.attribute.missing_name`。`GdScriptParserRangeInvariantTest` 校验 ERROR 诊断的代码已登记、范围有效，以及恢复链接指向本次结果中实际存在且支持该槽位的节点。
- owner frame 与可达性清扫：在槽位解析前 push owner frame，完成槽位后 pop 暂存（先 pop 后进 suite，防止 body 诊断误绑头部 facet），节点建成时绑定 `RecoveryLink(owner, facet)`。参数默认值帧延迟到签名接纳参数时绑定，rest 参数重建时迁移，被丢弃的重复参数不作为 owner。`parse()` 返回前按对象身份遍历最终 AST；无存活 owner 时，只沿显式授权链查找支持该 code 的存活 owner/facet，例如将丢弃的 lambda 默认值中的未闭分组归给函数签名。没有合法目标时保留诊断但清空链接。
- 位置决定孤立闭合符的归属：同行语句尾诊断落在原语句的 `statement.trailing`，独立行落在存活的套件或类体，真正无宿主的顶层垃圾保持独立。套件熔断诊断归属存活宿主的 `suite.body`，两者都不使用 `SourceFile` 作为兜底。
- match guard、enum 值和 elif 条件失败时，槽位带恢复标记，宿主继续存活。字典 pattern 保留可用的 key：例如 `{1+}` 的受损表达式仍可作为 `DictEntry` 的 key，`value` 为 `null`，因为没有写冒号。显式 `:` 后缺 value 是另一种情况，使用非空恢复节点表示失败。
- 参数顺序诊断不丢参数（有意偏离 vendor 的丢弃行为，保留身份与位置供编辑器消费）：mandatory-after-optional / rest 带默认值 / rest 后参数报错后参数仍留在签名列表；rest 参数的 variadic 重建保留已解析默认值与参数 range。重复名参数仍丢弃。
- C2 存活偏差：assert/const/match 值失败后语句带 marker 存活并消费成功路径尾部（vendor 丢弃该节点，此处为有意优于 vendor 的恢复）；登记同步集早停差异——存活路径把同步关键字当作槽位内容继续解析（`assert(1+, func(): pass)` 的 `func(): pass` 进消息槽），垃圾尾部比旧行为多一条 endStatement 类诊断。
- **已知限制（登记不修）**：跨行分组的 continuation 行跳闸（>256 层病态嵌套与跨行分组同时出现）会破坏作用域归属——分组的 `pushMultiline` 丢弃了 pending 的 INDENT，但 tokenizer 缩进栈已记录新层级，跳闸后失败帧先 pop multiline 再消费分组剩余内容，使 continuation 行的 DEDENT 成为无 parser 帧认领的孤儿布局，`poisonedLineIndent` 又取跳闸 token 所在行（continuation 行）而非逻辑构造起点行，导致闭合行被误判为恢复边界。此情形下解析仍终止、仍产生有效诊断、不抛异常，满足最低恢复契约；回归护栏（`multilinePoisonedPatternTerminatesWithDiagnostics`）仅钉终止性与诊断存在性，不钉作用域归属。正确修复需 tokenizer 暴露缩进栈深度 + parser 跟踪布局作用域数以识别孤儿 DEDENT，复杂度与风险不成比例。

## 10. 关键实现机制备注

- `trailingLambdaEndByte`：lambda 体内层 `end_statement` 可能已消费后续 NEWLINE/DEDENT。AST 的外层构造右缘锚定在 lambda body 尾字节，不把后续布局算入范围；后续任意 `advance()` 使该覆盖值失效。
- lambda 前注释延迟-归还：`deferCommentsBefore`/`restoreDeferredComments` 经 `GdTokenizer.modifiableComments` 可变视图，把 `func` 关键字前的注释在 lambda 体解析期间移出 trivia 游标（既不属 lambda body，也不被体排水窃取），体毕归还游标头；`pendingAnnotations` 同模式 try/finally save/clear/restore 隔离，外层悬挂注解不被 lambda body 窃取。
- 分组内注释丢弃采用 **span 记录**（`groupingCommentSpans` + 各收集点跳过 span 内注释），避免游标前缀阻塞导致组内注释外泄（§5.4）。
- 后缀链构建：Pratt 后缀循环独占单个 `ArrayList<AttributeStep>` 收集，链结束一次性冻结 `AttributeExpression`（消除每步不可变列表复制的 O(n²)）；token 消费顺序与 multiline push/pop 时序与 vendor 一致。
- 块起点回填：`layoutBlockStart` 将多行套件或类体块的起点回填至行尾空白。
- `scan()` 续行以循环实现（JVM 无尾调用消除，防栈溢出）。

## 11. 已知限制与风险

已知限制（登记在案，见各引用条款）：

1. 表达式内部注释不保留在 AST（§5.4）。
2. 跨行分组 continuation 行跳闸可能影响作用域归属（仅 >256 层病态嵌套且跨行分组；§9）。
3. 语义型检查留给分析层，例如赋值目标类型、调用目标类型和隐式 `super()` 上下文（§5.6）。
4. 补全入口只分类语法上下文与范围，不生成候选、不提供 signature-help DTO、不做增量解析。

风险登记：

1. **运算符优先级**：混合运算符的 AST 结构以 §5.8 为准，修改 Pratt 规则时需覆盖这些组合。
2. **Unicode/列换算**：内部的 Godot 字符列与公开的字节列分别使用各自口径；AST 和诊断一律通过 `SourceTextIndex` 从物理字节位置换算。
3. **EOF 合成坐标**：Godot 在 EOF 合成 `NEWLINE` 时行号 +1，token end 行列可能落在非物理行；`Range` 一律以物理字节游标换算，合成 token 锚定物理 EOF，测试已覆盖。风险等级：低。
4. **恢复死循环**：所有恢复/同步循环有进度保证与 suite 空语句计数上限；错误语料测试带超时。风险等级：低。
5. **Godot master 漂移**：基线锁定 commit `c016f22`；升级走独立评估（§2）。风险等级：低。
6. **pattern 形状适配**：match pattern 在本项目 AST 中以 `Expression` 表达（如 `PatternBindingExpression`），不同于 Godot 的 `PatternNode`。修改时注意 key-only 与缺损 value 的区别（§5.2）。
7. **性能**：§1.2 的数据仅为历史量级参照，目前无回归阈值。

## 12. 纯 Java 补全上下文合同

- 入口：`GdScriptParserFacade.parseCompletionContext(String source, long byteOffset)`，每次创建独立 parser 与 token 视图，可并发共享 facade，不依赖原生库。
- DTO：`frontend.gdscript.CompletionContext(kind, replaceableRange, receiverRange)`；kind 为 `MEMBER_ACCESS` / `IDENTIFIER` / `TYPE_POSITION` / `CALL_ARGUMENT` / `PARAMETER_NAME` / `SUITE_START` / `EXPECTED_KEYWORD` / `UNKNOWN`。仅成员访问携带 receiver；范围使用 UTF-8 字节偏移、零基行与字节列，光标限制在物理源范围内。
- 替换范围覆盖光标下完整名字，而非仅已输入前缀；缺名成员的零宽范围锚定点号末端，空槽锚定光标。receiver 覆盖点号左侧 primary/postfix 片段，包括分组括号、调用、下标与 get-node 路径；AST 存活时用链结构确定起点。
- 共享边界优先左侧名字：`node|.pa` 为 IDENTIFIER(`node`)，`a.b|.c` 补全 `b`，`Outer|.Inner` 补全类型名 `Outer`。字符串/注释起点之前仍在代码侧，`target(|"s")` 为 CALL_ARGUMENT；进入引号或字符串前缀后为 UNKNOWN，未闭合字符串末端仍 UNKNOWN。
- get-node 回退支持普通字符串路径段、`/%` 段和独立 `%`；已有完整左操作数后的 `%` 为取模，不吸收到右侧 receiver。健康 AST 与目标链被丢弃两种状态均测试，避免幸存 extent 掩盖回退缺陷。
- AST 存活时使用其类型/标识符与链 extent；AST 被恢复丢弃时按 token 回退，保留 `super.`、受损成员链、具名 callee、显式类型槽与 EOF 未闭合调用的上下文。点分类型名优先于成员名；标识符实参优先于外围调用实参区；缺名点号及其空首实参位优先于调用实参区。
- 托管 token 视图使用真实括号 token，跳过其他 `ERROR`，单独保留未闭合字符串区间。表达式/签名分组启用 multiline，类型容器括号仅继承；function/lambda 使用自己的硬布局与有效缩进边界，签名闭合后返回类型不继承签名的启用帧。
- `as`/`is` 仅算符角色开启类型槽，`is not` 可跨 `not`；成员名/get-node 路径中的同名关键字不触发类型补全。函数体/字典冒号、推断标记 `:=`/`: =` 不视为显式类型槽；签名参数表不视为调用实参区。
- `is` 的可选 `not` 只作用于紧随的首个非布局 token；类型替换范围仅包含自身属于类型槽的名字，`for x: Array[int]|in arr:` 不覆盖右侧 `in`。`func` 待开启参数表、返回类型/无体函数、INLINE/INDENTED body 分别维护生命周期；无效头重判当前 token，硬换行终止无体声明，所有函数帧退出恢复外层逻辑缩进。INLINE 显式续行不因物理行变化提前退出。
- 类型状态区分完整类型与后续表达式：guard 的 `when` 不延续前面的 cast 类型；容器 cast 完成后可补成员或下标表达式，点分类型后的 `[` 亦是表达式下标。字符串 `extends` 的点分内部类仍为 TYPE_POSITION，字符串内部仍 UNKNOWN。
- INDENTED 函数帧局部维护 match 模式头；模式逗号不退出 lambda，分支 body 尾逗号仍可恢复外层调用。硬换行或类型状态失效时清理连续非 multiline 类型帧，再处理 RETURN_TYPE 与当前 token，不清理签名内合法继承 multiline 的类型容器。
- 未闭类型在硬换行之后，只对紧邻、比类型声明逻辑行缩进更深且整行可信的类型片段补全。独立声明或语句、祖先分隔符以及不足缩进的光标均不触发；这不延长类型 multiline 或放宽严格解析。
- 字典值和声明/形参的推断初始值空槽返回 `IDENTIFIER`；合法签名的首个及后续参数名槽返回 `PARAMETER_NAME`，同逻辑行的函数体空槽返回 `SUITE_START`，中缀 `not` 的空槽返回 `EXPECTED_KEYWORD`（预期 `in`）。只在没有名字时检查这些槽，优先于调用实参回退。非法换行签名不提供空参数名分类。
- 与解析器共享惰性括号扫描器。最近同类祖先优先配对，遇异类替换或祖先截断只记录有效终点，不将错闭合当作 primary。未闭帧在硬结构边界停止，不泄漏布局或实参槽；`[1)\n\ta.\n\tb|` 为 `IDENTIFIER`，`(a.\n\tb|)` 仍为 `MEMBER_ACCESS`。
- EOF 无最终换行的 `target(`、`target(1,`、`target(1` 均为 `CALL_ARGUMENT`。
- 从旧五分类入口升级的调用方参见 [0.8.0 迁移指南](architecture-overview.md#4-migrating-to-080)。候选生成不属于 gdparser。

## 13. 参考资料

- Godot 源码 vendor 副本（commit `c016f22009aa773f0b2e6fce72baec168746491f`）：`vendor/godot/gdscript_tokenizer.h`、`vendor/godot/gdscript_tokenizer.cpp`、`vendor/godot/gdscript_parser.h`、`vendor/godot/gdscript_parser.cpp`（上游路径：`modules/gdscript/`）
- 仓库文档：[架构总览](architecture-overview.md)
