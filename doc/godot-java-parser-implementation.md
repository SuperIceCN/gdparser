# 纯 Java GDScript Parser 实施文档（Godot 官方 Parser 移植版）

本文档是纯 Java GDScript parser（包 `frontend.gdscript`，Godot 官方 tokenizer/parser 的移植版）的事实源：定位与现状、架构约定、AST 产出契约、与 tree-sitter 路径的 parity 规则、容错机制、测试策略与已知限制。移植已完成并进入维护期，本文档只保留稳态事实，不保留阶段性过程记录。仓库级架构总览见 `doc/architecture-overview.md`，tree-sitter 路径见 `doc/tree-sitter-ng-gdscript-implementation-plan.md`。

## 1. 定位与现状

### 1.1 定位与动机

- 双 parser 并存：`frontend.gdscript` 为纯 Java、无 native 依赖的移植版，忠实对齐 Godot 官方 tokenizer 与 parser 的词法/语法/错误恢复行为，直接产出 `frontend.ast` 的 AST；tree-sitter 路径（`infra.treesitter` + `frontend.cst` + `frontend.lowering`）保持原样，作为对照与回退，两路在 `frontend.ast` / `frontend.serialize` 汇合。
- 动机：tree-sitter-gdscript 的 GLR 未实现 Godot「换行即语句结束」的 tokenizer 级语义（`NEWLINE`/`INDENT`/`DEDENT` 由 tokenizer 显式产生，附带 pending 机制与 multiline 抑制规则），在换行/缩进边界与官方行为不一致；其 `ERROR` 节点是通用容错产物，恢复粒度不可控。Godot 官方 parser 有明确的 panic-mode + 同步集恢复机制，可为 LSP 提供可预测的诊断与部分 AST。
- 移植范围严格限定为 parser：裁剪语义分析（类型解析、常量折叠、符号表、补全钩子、注解验证等），只产出干净 AST 与语法诊断；补全能力继续由 tree-sitter 路径的 `CompletionContextAnalyzer` 承担。

### 1.2 当前状态

- 移植已完成：tokenizer、Pratt 表达式、全部语句/声明、panic-mode 恢复、注释/region trivia、Facade（`parse`/`parseStrict`/`tokenize`）与并发契约全部落地。
- 13 个 fixtures 全文件双路径 parity 通过（§5.7 (i)），由 `GdParserParityTest.fixtureFullFileParity` 动态发现断言。
- 性能基线（JIT 预热后一次性夹具测量，仅作量级参照，不设回归阈值）：13 个 fixtures（合计约 41.6 KB）全量轮均值约 4.38 ms，吞吐量级 ≈9.5 MB/s；单 fixture 均值 35.4–870.0 µs（最大为 5.3 KB 的 `Sample10_Operators.gd`）。环境：JDK 25.0.4、Xeon E5-2699 v4 @ 2.20GHz。
- 测试分层与各类规模见 §7；已知限制与风险见 §11。

## 2. Godot 参考基线

- 仓库：`godotengine/godot`，commit `c016f22009aa773f0b2e6fce72baec168746491f`（记录于 `vendor/godot/COMMIT_SHA`）。
- 参考文件已 vendor 进仓库，实施/维护时直接对照（仅作对照参考，不参与编译）：
  - `vendor/godot/gdscript_tokenizer.h` / `vendor/godot/gdscript_tokenizer.cpp`
  - `vendor/godot/gdscript_parser.h` / `vendor/godot/gdscript_parser.cpp`
- 移植代码与 vendor 源文件的对应关系以函数级 `///` 注释标注（如 `/// Port of GDScriptTokenizerText::check_indent`），保证双向可追溯；注释须自解释，不引用本文档章节编号。
- 升级 Godot 基线时须同步更新 vendor 副本与 `COMMIT_SHA`，并走独立的评估流程。
- 许可：Godot 为 MIT License。若未来直接复制其代码片段进发布产物，须附带 Godot 版权与许可声明。

## 3. 与既有代码基线的关系

- `frontend.ast` 解析器无关：sealed `Node`/`Statement`/`Expression` + record 节点；`Range` 为 UTF-8 字节偏移 + 0-based `Point(row, column)`（列为字节列）；集合组件构造时 `List.copyOf` 冻结。
- `AstFactory` 的 helper 签名耦合 `CstRange`/`CstNodeView`/`CstIssueKind`——新 parser **不使用** `AstFactory`，直接调用 record 构造器。
- `ErrorStatement`/`ErrorExpression` 的 `kind` 组件类型为 `CstIssueKind`（`ERROR`/`MISSING`）——语义通用，直接复用，不做包迁移。新 parser 自 sealed-outcome 恢复改造（§9）起在受损表达式槽位构造 `ErrorExpression` marker 并在缺名点号链上构造 `MissingAttributeStep`（仍不构造 `ErrorStatement`）；tree-sitter 路径行为不变。
- `CstToAstMapper` 深度耦合 tree-sitter grammar 的节点类型名与字段名——新 parser **不复用**该 mapper，直接构建 AST。
- 项目无 parser 接口抽象：`GdParserFacade` 是 tree-sitter 路径的唯一入口，新 parser 使用独立的 `GdScriptParserFacade`，两者不共享入口类。
- `LiteralExpression.kind` 的字符串集合是 AST 契约词汇表（§5.3）。
- 属性 get/set 块（`var x: int: get = a, set = b` 与内联 `get:`/`set(value):` 套件）：AST **丢弃**该信息（`VariableDeclaration` 仅含 name/type/value/static）；新 parser 正确消费该语法结构，同样不产出对应 AST 节点。
- 注释：Godot tokenizer 不将注释放入 token 流；新 parser 将注释作为 trivia 保留并分类（§5.4），对齐 tree-sitter 路径的 `CommentStatement`/`RegionDirectiveStatement` 产出。
- 诊断模型 `AstMappingResult(SourceFile, List<AstDiagnostic>)` 与 `AstDiagnostic(severity, message, nodeType, range)` 解析器无关，直接复用为新 parser 的返回类型。
- 序列化（`frontend.serialize`）按 record 组件反射工作，对新 parser 产出的 AST 天然可用，是 parity 对比的可靠工具。
- fixture 基线：13 个 fixtures 在 tree-sitter 路径全部零诊断、零 `Unknown*` 节点（`CstToAstMapperTest` 动态断言）。

## 4. 架构与移植约定

### 4.1 包结构与主要类型

包 `dev.superice.gdparser.frontend.gdscript`（`module-info.java` 已导出），主要类型：

- `GdTokenType`：token 类型枚举，逐一对齐 Godot `Token::Type`（`TK_*` 去前缀，省略 `TK_MAX`），含 displayName 对照。
- `GdToken`：record——类型、源文本切片、字面量种类（`GdLiteralKind`）、ERROR 消息载体（对齐 Godot `ERROR` token 的 `literal` 载体）、起止字节偏移与 1-based 行/列（内部对齐 Godot 坐标；对外经 `SourceTextIndex` 换算为 `Range`）。ERROR token 的源切片置空（字节区间精确即可），避免大文件错误密度下 O(n²) 内存。
- `GdTokenizer`：`GDScriptTokenizerText` 的移植，增量 `scan()`；注释 trivia 收集（§5.4）；缩进状态快照 push/pop（对齐 `push_expression_indented_block`，供 lambda 使用；空栈 pop 抛 `IllegalStateException`——Godot 为静默 no-op，此处按内部 bug 处理）。
- `SourceTextIndex`（包内）：UTF-8 字节偏移 ↔ 行列换算、行起始表；以**物理字节游标**为 `Range` 的唯一事实源（0-based 行、字节列）；Godot 在 EOF 合成的 token 行列不参与换算，锚定物理 EOF（§5.1）。
- `GdScriptParser`：递归下降 + Pratt，直接构建 `frontend.ast` 记录；内部持有诊断列表、panic 标志、multiline 栈、lambda 状态、嵌套深度预算（§9）。
- `GdScriptParserFacade`：公开入口——`parse(String) -> AstMappingResult`（容错）、`parseStrict(String) -> SourceFile`（遇首个 ERROR 诊断即抛 `IllegalStateException`，消息格式 `Parsing failed at <nodeType>: <message>`，语义对齐 `CstToAstMapper.mapStrict`；WARNING 不触发）、`tokenize(String) -> List<GdToken>`（**raw 扫描流**：无 parser 的 multiline 干预，可能含上下文相关的缩进诊断与结尾 EOF token，不作为合法源「零 ERROR」的判定依据；parser 实际消费的 token 流以 `parse` 内部为准）。Facade 无状态（每次调用创建 fresh parser/tokenizer），共享实例可安全并发。

### 4.2 数据流

```text
GDScript Source (String)
  -> GdScriptParserFacade
  -> GdTokenizer (token 流 + 注释 trivia + tokenizer 诊断)
  -> GdScriptParser (Pratt + panic-mode 恢复)
  -> SourceFile AST + AstDiagnostic list (AstMappingResult)
  -> (可选) AstSexprSerializer / AstSexprDeserializer
```

tree-sitter 数据流（`GdParserFacade -> CstAdapter -> CstToAstMapper`）保持不变，两条路径在 `frontend.ast` / `frontend.serialize` 汇合。

### 4.3 移植约定

- 遵循 AGENTS.md：record/密封接口、`var`、`List.of`、`List.getFirst/getLast`、switch 表达式、文本块、`///` 文档注释、record 字段不写注释（用类级 `@param`）、`@Nullable`/`@NotNull`、`requireXXX`/`checkXXX` 命名。
- 字符分类用 JDK `Character.isUnicodeIdentifierStart/isUnicodeIdentifierPart` + `_` 近似 Godot XID 表，并显式排除越界哨兵 0（JDK 将 NUL 等 identifier-ignorable 控制符判为标识符字符，不排除会在 EOF 处死循环）。
- 字面量不解码值：`GdLiteralKind` + 源切片承载（对齐 AST 契约）。
- 标识符判定移植 Godot `Token::is_identifier()`（`match`/`when`/`PI`/`TAU`/`INF`/`NAN` 可作名字）与属性名/节点路径名的 `is_node_name()` 集合。
- 未配对 UTF-16 代理项按 JDK UTF-8 替换规则计 1 字节（与 tree-sitter 路径字节流一致）。
- 不修改 build 脚本；`module-info.java` 的 exports 调整属于源码范畴。

## 5. AST 产出契约与差异决策

### 5.1 位置契约

- `Range`：UTF-8 字节偏移 + 0-based 行、字节列，与 tree-sitter 输出逐位一致。
- `Range` 的唯一事实源是**物理字节游标**：tokenizer 内部按 Godot 约定维护 1-based 行/列（列按字符计）仅供诊断消息；产出 AST 前经 `SourceTextIndex` 由字节偏移统一换算；Godot 在 EOF 合成的 token 行列不参与换算，锚定物理 EOF（§8.1 EOF 坐标条款）。换算逻辑有单测覆盖（含 CRLF、中文、emoji、tab、无末尾换行、末尾注释、末尾多行字符串）。

### 5.2 AST 形状适配（Godot AST → 本项目 AST）

以下形状均以 tree-sitter 路径产出为基准钉死，parity 测试据此断言：

- `elif` 链：Godot 在 false suite 中嵌套 `IfNode`；产出时折叠为 `IfStatement.elifClauses`（`ElifClause(condition, body)`）+ 可选 `elseBody`，范围与 tree-sitter 路径一致。
- 构造器：名为 `_init` 的 `func` 产出 `ConstructorDeclaration`（无 `name`/`isStatic` 组件），其余函数产出 `FunctionDeclaration`。构造器可显式声明返回类型：无 `->` 时 `returnType = null`，有 `-> T` 时保留对应 `TypeRef` 及源范围。
- 后缀链：Godot 逐节点构造 `SubscriptNode`/`CallNode`；产出时按现有路径形状折叠：`obj.a.b(1).c[2]` → 单个 `AttributeExpression(base, [AttributePropertyStep("a"), AttributeCallStep("b", [1]), AttributeSubscriptStep("c", [2])])`；`arr[0]`（下标直挂 primary）→ `SubscriptExpression(base, [index])`；`f(x)` → `CallExpression(callee, args)`。
- `super`：`super.foo()` → `AttributeExpression(IdentifierExpression("super"), [AttributeCallStep("foo", args)])`；`super()` → `CallExpression(IdentifierExpression("super"), args)`（不引入 `is_super` 类标记）。
- 推断类型标记 `:=`：`var`/`const`/参数的 `COLON`+`EQUAL` 识别为推断标记，`type` 组件为非空 `TypeRef`——`sourceText` 为从 `:` 起至 `=` 止的源切片（仅去首尾空白；紧邻写法为 `":="`，`: =` 形态保留内部空白为 `": ="`），range 覆盖整个标记含内部空白。**不是** `null`。
- `VariableDeclaration.sourceNodeType`：契约字符串，`var` → `"variable_statement"`、`const` → `"const_statement"`，照抄 tree-sitter 路径取值（序列化会比较该组件）。
- `not in`：产出 `BinaryExpression("not in", left, right)`；Godot 的 `NOT` infix 规则在产出层折叠为该形态（与 `is not` 同理）。
- `assert`：语句位置的 `assert(cond, msg)` 产出 `AssertStatement(condition, message)`，不是 `ExpressionStatement`。
- Lua 风格字典 key：`{ name = 1 }` 的 key 产出**合成** `LiteralExpression("string_name", "&\"name\"")`（源中不存在的拼接文本，range 以 tree-sitter 路径产出为准）；Python 风格 `key: value` 的 key 正常映射。
- 类体 `pass`（偏差 D4）：产出 `PassStatement`（Godot 不产出节点；tree-sitter 路径保留）。
- key-only 字典 pattern（如 `{"name"}:`）：tree-sitter 路径丢弃 key 并发 `WARNING` 诊断（`DictEntry` 无法表达缺失 value）。新 parser 对齐该行为（丢弃 + WARNING），登记为 AST 表达力缺口，未来随 AST 演进补全。
- 函数/信号/参数：`FunctionDeclaration`/`SignalStatement`/`Parameter`（含 `variadic` 对应 Godot rest 参数 `...`）。
- 受损表达式槽位（如 `if` 缺条件、`var x =` 缺初始化器、`1 +` 缺右操作数）：宿主构造带 marker 存活——零消费槽位填零宽 `ErrorExpression(CstIssueKind.MISSING, slotName, "", [extentEnd(), extentEnd()))`，有消费的失败填 `ERROR` kind 片段（`nodeType` `"ERROR"`，覆盖实际消费区间 `[slotStart, extentEnd())`），部分节点（如带 marker 右槽的 `BinaryExpression`）原样保留；语句/声明宿主（if/while/for/assert/const/match 头、match guard/pattern、enum 值、elif 分支）随之存活（§9 sealed-outcome 协议）。缺名点号在链上填 `MissingAttributeStep`（点号末端零宽）并继续续折。
- 恢复占位与诊断归属：`ErrorStatement` 仍不构造（无法承载失败的语句级构造按丢弃 + sync 处理）；缺闭合符（分组/数组/字典/调用/下标/注解/preload/assert 共 8 处）发零宽 ERROR 诊断，带稳定 `code`（`parse.missing_closer.*`）与同代 `RecoveryLink`（owner 为最终 AST 中可按对象身份到达的承载构造，facet 为点分槽位名；owner 未存活时 link 在 `parse()` 返回前被清除，保留 code/range/message；link 不跨 serde 持久化）。非法源不做 AST parity（§5.7）。

### 5.3 字面量 kind 词汇表（AST 契约）

`LiteralExpression.kind` 固定取值集合（沿用 tree-sitter 节点名，tree-sitter 路径不变）：`integer`、`float`、`string`、`string_name`、`node_path`、`true`、`false`、`null`。`sourceText` 保留源切片（含引号/前缀）；Lua 风格字典 key 的合成 `string_name` 文本见 §5.2。

内置常量 `PI`/`TAU`/`INF`/`NAN`：**不进入**该词汇表。Godot 将其折为数值 `LiteralNode`，但 tree-sitter 路径产出 `IdentifierExpression`；新 parser 对这些 token 同样产出 `IdentifierExpression` 以维持 parity（数值折叠属裁剪项）。

### 5.4 注释与 region 指令（偏差 D2）

- tokenizer 将注释作为 trivia 收集（文本含前导 `#`、范围、是否整行/行尾），不进入 token 流。
- 插入规则：注释按其源位置插入**所属 suite/类体的语句序列**，作为独立 `CommentStatement`；行尾注释位于其所跟随语句**之后**（同级后继语句），整行注释按源顺序占位。示例：`var x = 1 # tail` → 类体语句序列 `[VariableDeclaration, CommentStatement("# tail"), ...]`；函数体内整行注释按源位置排在相邻语句之间。
- `#region <label>`/`#endregion` 注释分类为 `RegionDirectiveStatement("start"/"end", label)`（`label` 为关键字后文本去首尾空白，空则为 `null`；`#endregion` 的 label 恒为 `null`；`#regionfoo` 也算 start、label `"foo"`，`# region` 带空格则为普通注释）。
- 丢弃规则（两路径一致）：套件头行尾注释（`func f(): # c`、`if x: # c`、`else: # c` 等冒号后同行注释）不进入任何语句序列；match 体分支层级的整行注释同样被丢弃（分支 suite 内的注释按常规归属）。
- dedent 前尾窗口的整行注释归属：注释缩进 ≥ 所属 body 缩进时留在 body 内（如 `func f():\n\tpass\n\t# inner\nvar x = 1\n` 中 `# inner` 留在 f 体内），否则归属外层序列（如 `func f():\n\tpass\n# hello\nfunc g():...` 中 `# hello` 归属顶层）；body 中间的整行注释只要下一行代码仍属于该 body 即留在 body 内（col 0 亦然）。
- 表达式内部注释（如数组字面量元素间的 `[\n\t1, # one\n\t2\n]` 或调用/注解实参列表内）：旧路径产出 `UnknownExpression`（nodeType `comment`）+ WARNING；新 parser 的注释不进入 token 流、无法复现该形状，登记为已知 parity 差异——新 parser 静默忽略该类注释（实现：分组闭合时记录 span，各排水点跳过 span 内注释），parity 语料排除此类片段。
- dedent 尾窗口的混合缩进注释组（col-0 注释与缩进注释相邻出现在 body 末尾，如 `func f():\n\tpass\n# low\n\t# high\nvar after = 1\n` 或多层嵌套 `class Outer:\n    class Inner:\n        pass\n# low\n        # high\nvar after = 1\n`）：旧路径 scanner 对 col-0 注释做下一行 lookahead 回填缩进（上游 issue #85/#42 承认的启发式缺陷），把整组注释回填进 body；新 parser 应用一致的缩进阈值规则，整组归属外层序列。该差异在**单层与多层 dedent 下均出现**，登记为已知 parity 差异，parity 语料排除此类片段；孤立的单条注释（col-0 外出、缩进保留、body 中间 col-0）两路径一致，不受影响。

### 5.5 负号吸收（偏差 D1）

- Godot tokenizer 在一元位置将 `+`/`-` 吸收进数字字面量（使 `-2 ** 2` 解析为 `(-2) ** 2`）；本项目 AST 契约将符号建模为 `UnaryExpression("-", operand)`（serde 测试已钉死该形状）。
- 决策：**不吸收**，`-`/`+` 始终作为独立 token，parser 按 `PREC_SIGN` 解析一元运算；Godot 的 `can_precede_bin_op` 符号吸收分支随之移除。普通一元场景两路径一致。
- 不吸收时 `-2 ** 2` 在 Godot 优先级下解析为 `-(2 ** 2)`（一元操作数只按 `PREC_SIGN` 解析，`PREC_POWER` 更高），而 tree-sitter 路径产出 `(-2) ** 2`——该分歧并入 D3 优先级分歧集（§5.8），不单列。

### 5.6 诊断对齐政策

- 语法错误消息语义对齐 Godot（可精简 `vformat` 细节）；3.x 移除关键字（`export`/`tool`/`onready`/`setget`/`yield`/旧 RPC 关键字）报带迁移提示的错误。
- Godot 在 parse 阶段的「语义型」检查中，仅移植无歧义的纯语法判定：**重复参数名、默认参数顺序、`break`/`continue` 循环上下文、构造器 `return` 带值**；依赖符号表/类型的一类一律裁剪。每条移植的检查都有单测钉死。
- 识别并**暂缓**的 parse 时检查（均属依赖节点类型/上下文的语义型判定，留给分析层；新 parser 产出结构、不报诊断）：赋值目标类型检查（`Only identifier, attribute access, and subscription access can be used as assignment target.`）、调用目标类型检查（`Cannot call on an expression. Use ".call()" if it's a Callable.`，故 `f(1)(2)`、`$Node(x)`、`PI()` 等新 parser 不报错）、隐式 `super()` 函数上下文检查（`Cannot use implicit "super" call outside of a function.`）。暂缓行为有钉死测试，防止未来移植时意外加回。
- tokenizer 错误（非法字符、未闭合字符串、缩进混用等）进入诊断列表，severity 为 ERROR，不中断扫描。

### 5.7 parity 范围定义

parity 语料分三类管理（非法源不适用 parity，另有独立验收规则）：

- **(i) 共同支持且结构一致的语法**：双路径 AST 经 `AstSexprSerializer` 序列化后**逐字符一致**（含全部 range）。13 个 fixtures 均属此类（全部零诊断、零 `Unknown*` 节点，由 `CstToAstMapperTest` 断言；`GdFixtureParityAuditTest` 确认不含 §5.8 分歧组合）。
- **(ii) Godot 合法但旧路径报错/丢失的语法**：不作 AST parity 要求；新 parser 按 Godot 产出，或登记信息丢失。已实证登记：
  - `$%Child`、`$Root/%Child`（`%` 位于 `$` 后或 `/` 后）：Godot 合法，旧路径报 ERROR——新 parser 按 Godot 产出 `GetNodeExpression`。
  - key-only 字典 pattern（`{"name"}:`）：旧路径丢弃 key + WARNING——对齐该行为并登记为 AST 表达力缺口（§5.2）。
  - 脚本前缀/类体中的独立文档字符串（如 `"""doc"""\nclass_name X\n`、`var x = 1\n"""doc"""\nvar y = 2\n`）：Godot 将其消费为文档字符串、不产出节点；旧路径产出 `ExpressionStatement(LiteralExpression("string"))`——新 parser 按 Godot 落地（不产节点），parity 语料排除此类片段。
  - 嵌套类缩进体首行的 `extends`（`class Inner:\n\textends Base\n\tpass\n`）：Godot 将其设为类超类、不产出节点；旧路径在 body 内产出 `ExtendsStatement` 且 `extendsTarget = null`——新 parser 按 Godot 落地（`extendsTarget = "Base"`），parity 语料排除此类片段。
  - match 体 `pass` 分支（`match v:\n\t1:\n\t\tpass\n\tpass\n`）：Godot 消费 `pass` 且不产出分支；旧路径整体报 ERROR——新 parser 按 Godot 处理，parity 语料排除。
  - match 层级注解：`@warning_ignore(...)` 两路径均消费并丢弃（旧路径静默、Godot 挂到分支后由本项目 AST 丢弃）；其他注解（如 `@export`）Godot 报 `Annotation "%s" is not allowed in this level.`，旧路径静默丢弃——新 parser 按 Godot 报错，parity 语料排除。
  - 纯注释套件体（`func f():\n\t# only\nvar x = 1\n`）：注释行不建立缩进，Godot/新 parser 报 `Expected indented block...`；旧路径接受并把注释作为 body 唯一内容——parity 语料排除。
  - `assert` 超过两个实参（`assert(1, "m", 3)`）：Godot/新 parser 报 `Expected ")" after assert expression.`；旧路径静默丢弃多余实参——parity 语料排除。
  - 类体/顶层匿名函数（`func(): pass\n`）：Godot/新 parser 报 `Expected function name after "func".`；旧路径接受并产出 `FunctionDeclaration("<anonymous>")`——parity 语料排除。注：**suite 内**的语句级 `func`（命名或匿名）两路径行为一致——均产出 `FunctionDeclaration`（匿名时 `"<anonymous>"`），这是 AST 契约形状而非分歧。
  - `static` 与成员之间换行（`static\nvar x = 1`、`static\n# c\nvar x = 1`）：Godot/新 parser 立即报 `Expected "func" or "var" after "static".`（vendor 逐字如此）；旧路径接受并把 `static` 跨进成员节点（其间注释随之丢弃）——parity 语料排除。
  - match 体分支间 col-0 整行注释（`match v:\n\t\t1: pass\n# low\n\t\t2: pass\n`）：Godot 视注释为 trivia 正常解析；旧路径 grammar 报 ERROR——新 parser 按 Godot 解析（注释随 match 分支层丢弃），parity 语料排除。match 体**尾部** col-0 注释（后续代码 dedent）旧路径正常并归属外层，两路径一致。
- **(iii) 运算符优先级分歧组合**：见 §5.8（D3 已确认与 Godot 锚定）；parity 矩阵对这些组合断言 Godot 结构、不作双路径一致要求。
- **非法源**（独立验收，不参与 parity 分类）：不要求 AST 一致；要求新 parser (a) 不抛异常、可终止，(b) 产生 ≥1 条 ERROR 诊断且 range 有效，(c) synchronize 后的兄弟声明结构正确（如类体中一个坏函数之后的函数完整解析）。
- 已知差异白名单：仅本条 (ii)(iii) 登记项；新增豁免须在本节登记原因与样例。

### 5.8 决策 D3：运算符优先级分歧（与 Godot 锚定）

实证三方分歧（Godot `PREC_*` vs 固定版 tree-sitter grammar）：

| 合法表达式 | 旧路径 AST（tree-sitter） | Godot 结构 |
| --- | --- | --- |
| `2 * 3 ** 2` | `(2 * 3) ** 2`（`**` 与 `*` 同级、左结合） | `2 * (3 ** 2)`（`PREC_POWER` 高于 `PREC_FACTOR`） |
| `-x ** 2` | `(-x) ** 2`（一元高于幂） | `-(x ** 2)`（`PREC_SIGN` 低于 `PREC_POWER`） |
| `-2 ** 2`（D1 不吸收时） | `(-2) ** 2` | `-(2 ** 2)` |
| `1 + 2 as int` | `1 + (2 as int)`（`as` 高于加法） | `(1 + 2) as int`（`PREC_CAST` 低于加法） |

决策：新 parser 的 `PREC_*` 优先级表与 Godot 锚定；上表组合在 parity 矩阵中标注为「Godot 正确性断言」而非 parity 断言。13 个 fixtures 经审计不含分歧组合（`**` 仅出现于 `x ** 2`/`a ** b`/`2 ** 3 ** 2` 等两路径同构形态，`as` 左操作数均为 primary），fixture parity 不受影响。tree-sitter 路径不做任何调整，分歧作为其已知缺陷登记。

## 6. 移植范围清单

| 类别 | 内容 | 处置 |
| --- | --- | --- |
| 移植 | token 类型全集、缩进/换行/续行机制、字面量与字符串全形态、Pratt 表达式、全部语句/声明解析、panic-mode + synchronize 恢复（三处接入点）、suite 空语句计数上限、multiline push/pop（类型解析继承调用上下文、不自行开启）、lambda 缩进快照机制 | 行为对齐 Godot |
| 改造 | 注释（Godot 不入 token 流 → 本项目保留 trivia 并分类为 `CommentStatement`/`RegionDirectiveStatement`） | 偏差 D2，§5.4 |
| 改造 | 负号吸收进数字字面量 | 不吸收，偏差 D1，§5.5 |
| 改造 | 类体 `pass` 保留 `PassStatement`（Godot 不产出节点）；`PI`/`TAU`/`INF`/`NAN` 产出 `IdentifierExpression`（不折数值） | parity 适配，§5.2/§5.3 |
| 改造 | `elif`（Godot 嵌套 `IfNode` → AST `ElifClause`）、后缀链（`SubscriptNode`/`CallNode` → `AttributeExpression`+steps/`CallExpression`/`SubscriptExpression`） | AST 形状适配，§5.2 |
| 裁剪 | 语义分析、类型解析、常量折叠（含内置常数数值化）、符号表、补全、注解验证、警告、文档提取、依赖缓存、二进制路径 | 不移植 |
| 保留不动 | tree-sitter 全链路、`frontend.cst`、`frontend.lowering`、`frontend.serialize` | 零行为变更 |

裁剪明细（Godot parse 阶段携带但不移植的语义耦合）：

- `GDScriptAnalyzer` 交互、`DataType` 解析、类型约束与 `resolved_type`、`reduced`/`reduced_value` 常量折叠。
- `SuiteNode` 局部符号表、标识符 source 解析、成员索引重复检查（纯语法层面可判定的诊断除外，§5.6）。
- 代码补全状态机与 `make_completion_context` 钩子、编辑器 cursor 跟踪。
- 注解名称/目标/参数个数验证与 `@tool`/`@icon` 等 parser-time 动作（仅解析注解语法）。
- 警告系统、工具构建的文档注释提取、`GDScriptParserRef`/`GDScriptCache` 依赖解析、二进制 tokenizer 路径。

## 7. 测试策略与验收

- 分层：`SourceTextIndexTest`（坐标换算）→ `GdTokenizerTest`（token 流）→ `GdScriptParserTest`（合法语法 AST）→ `GdScriptParserErrorRecoveryTest`（容错与 marker 形状）→ `GdScriptParserRangeInvariantTest`（范围包络不变量）→ `GdParserParityTest`（双路径一致）→ `GdScriptParserFacadeTest`（入口/并发）；另有 `GdParityReferenceTest`（钉死形状断言）与 `GdFixtureParityAuditTest`（fixtures 分歧组合审计，动态用例）支撑。
- parity 实现：复用 `AstSexprSerializer` 输出做逐字符 diff；fixture 遍历模仿 `CstToAstMapperTest` 的动态 `.gd` 发现模式；合法语料经 `assertCleanParity`（AST 一致 + 双端零 ERROR）断言；诊断列表不参与合法语料 parity（非法语料与 §5.7 (ii) 登记项按其条款断言）。
- 语料：优先内联字符串；确需文件时放 `src/test/resources/gdscript` 并纳入动态遍历；tree-sitter 路径 AST 参照快照存于 `src/test/resources/gdparser-parity/*.sexp`。
- 迭代纪律：迭代期只跑目标测试类（`script/run-gradle-targeted-tests.sh --tests <Class>`）；改动后跑既有前端测试回归（`CstToAstMapperTest`、`AstSexprSerdeTest` 等）确认 tree-sitter 路径零影响；PR 前全量 `./gradlew clean build --no-daemon --info --console=plain`。
- 当前测试规模（`@Test` 计数，动态发现用例另计）：`GdTokenizerTest` 51、`SourceTextIndexTest` 12、`GdScriptParserTest` 234、`GdScriptParserErrorRecoveryTest` 79、`GdScriptParserRangeInvariantTest` 2、`GdParserParityTest` 22、`GdParityReferenceTest` 13、`GdScriptParserFacadeTest` 11。
- Godot 对照方法：本环境无编辑器二进制，以 vendor 源码追踪为基准（commit `c016f22`），行列号按 Godot 字符列口径；代表性错误场景的对照结论以注释记录在用例上。

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
- 非法源验收契约（§5.7 非法源条款的落地）：不抛异常、可终止；≥1 条 ERROR 诊断且 range 不越界（UTF-8 字节口径）；synchronize 后兄弟声明结构正确。
- sealed-outcome 恢复协议：表达式族解析返回密封接口 `ParseOutcome<T>`（`Ok`/`Missing`/`Failed`/`Damaged`）而非可空引用——`Missing` 为零消费且未推诊断（槽位宿主经 `absorbSlot` 补发泛化消息并填零宽 MISSING marker），`Failed` 为已推诊断、无节点（宿主填消费区间 ERROR 片段，不补发），`Damaged` 为已推诊断的部分节点（宿主保留，不补发）——单槽位单根因；Pratt 循环在非 `Ok` 左操作数处停折（与旧 null 停折逐点等价），后缀链内缺名点号续折（`MissingAttributeStep` 充当折叠障碍：后续 `(`/`[` 不再折向前一属性 step）、受损实参/索引走完清理建成节点后即返（不再续折后续 suffix）。语句分派：`Missing` 走旧 null 路径逐字不变；`Failed` 复用同一条丢弃路径（省泛化诊断）；`Damaged` 仅在真语句边界（`isStatementEndToken() || lambdaEnded`，`isStatementEnd()` 在 lambda 内恒真不能作门控）存活为 `ExpressionStatement`，否则按丢弃路径处理。
- 诊断 code 词汇表（包私有 `DiagnosticCodes` 集中登记）：`parse.missing_closer.{grouping,array,dictionary,call,subscript,annotation,preload,assert}`；`parse.missing_expression.<slot>`（由点分槽位名把 `.` 换 `_` 机械推导，与 marker `nodeType`/`RecoveryLink.facet` 共用词汇）：`variable.initializer`、`constant.value`、`parameter.default`、`return.value`、`annotation.argument`、`array.element`、`dictionary.key`、`dictionary.value`、`call.argument`、`subscript.index`、`grouping`、`binary.right`、`unary.operand`、`ternary.condition`、`ternary.else`、`assignment.value`、`await.operand`、`preload.path`、`assert.condition`、`assert.message`、`match.value`、`match.guard`、`match.pattern`、`dictionary_pattern.key`、`enum.value`、`if.condition`、`elif.condition`、`while.condition`、`for.iterable`；另有 `parse.attribute.missing_name`、`parse.super.{missing_access,missing_name,missing_call}`、`parse.signature.{mandatory_after_optional,rest_with_default,parameter_after_rest}`。槽位补发之外的既有诊断（如 synchronize/endStatement 类）暂不分配 code。
- owner frame 与可达性清扫：可存活构造在槽位解析前 push owner frame、槽位解析完立即 pop 暂存（先 pop 后进 suite，防止 body 的闭合符诊断误绑头部 facet），节点建成时绑定 `RecoveryLink(owner, facet)`；参数默认值的 frame 延迟到签名最终接纳该参数时绑定（rest 参数的 variadic 重建随帧迁移到重建后节点，被丢弃的重复名参数的 frame 一并丢弃）；`parse()` 返回前按对象身份遍历最终 AST 建立可达集合，owner 不可达的 link 一律清空（不回绑外层 owner）。facet 词汇（开 frame 的位点）：`variable.initializer`、`constant.value`、`parameter.default`、`return.value`、`assert.condition`、`assert.message`、`assert.arguments`、`match.value`、`match.guard`、`match.pattern`、`if.condition`、`elif.condition`、`while.condition`、`for.iterable`、`enum.value`、`expression`（表达式语句）、`annotation.arguments`。
- C3 槽位 marker：match guard / enum 值 / elif 条件 / 字典 pattern key 失败不再置 null 或丢槽——guard/enum 值/elif 填槽位 marker 且宿主存活（enum `memberEnd` 扩展到 marker 末端，elif 保留分支与健康 body）；字典 pattern 受损 key 有 `: sub-pattern` 载体时保留在 entry，无载体（无冒号、子 pattern 被丢、`..` 误用丢 entry 分支）时整个字典 pattern 回退为 `ErrorExpression(ERROR)` 片段（覆盖实际消费前缀 `[patternStart, extentEnd())`，健康兄弟 entry 一并损失——`DictEntry` 无法表示受损 key 的 key-only 形态，不为受损 key 伪造未开启的 value 槽位）；key-only 警告只对未受损 key 发出。这些槽位的 `null` 语义收窄为「从未书写」。
- 参数顺序诊断不丢参数（有意偏离 vendor 的丢弃行为，保留身份与位置供编辑器消费）：mandatory-after-optional / rest 带默认值 / rest 后参数报错后参数仍留在签名列表；rest 参数的 variadic 重建保留已解析默认值与参数 range。重复名参数仍丢弃。
- C2 存活偏差：assert/const/match 值失败后语句带 marker 存活并消费成功路径尾部（vendor 丢弃该节点，此处为有意优于 vendor 的恢复）；登记同步集早停差异——存活路径把同步关键字当作槽位内容继续解析（`assert(1+, func(): pass)` 的 `func(): pass` 进消息槽），垃圾尾部比旧行为多一条 endStatement 类诊断。
- **已知限制（登记不修）**：跨行分组的 continuation 行跳闸（>256 层病态嵌套与跨行分组同时出现）会破坏作用域归属——分组的 `pushMultiline` 丢弃了 pending 的 INDENT，但 tokenizer 缩进栈已记录新层级，跳闸后失败帧先 pop multiline 再消费分组剩余内容，使 continuation 行的 DEDENT 成为无 parser 帧认领的孤儿布局，`poisonedLineIndent` 又取跳闸 token 所在行（continuation 行）而非逻辑构造起点行，导致闭合行被误判为恢复边界。此情形下解析仍终止、仍产生有效诊断、不抛异常，满足最低恢复契约；回归护栏（`multilinePoisonedPatternTerminatesWithDiagnostics`）仅钉终止性与诊断存在性，不钉作用域归属。正确修复需 tokenizer 暴露缩进栈深度 + parser 跟踪布局作用域数以识别孤儿 DEDENT，复杂度与风险不成比例。

## 10. 关键实现机制备注

- `trailingLambdaEndByte`：lambda 体内层 `end_statement` 可能已消费后续 NEWLINE/DEDENT，Godot 将其算入外层 extent 而 tree-sitter 不算——按 parity 锚定（tree-sitter 口径），lambda 记录 body 尾字节，作为 var/const/return/表达式语句/赋值/二元/三元/一元/`not in`/`await` 右缘的覆盖值；后续任意 `advance()` 使其失效。
- lambda 前注释延迟-归还：`deferCommentsBefore`/`restoreDeferredComments` 经 `GdTokenizer.modifiableComments` 可变视图，把 `func` 关键字前的注释在 lambda 体解析期间移出 trivia 游标（既不属 lambda body，也不被体排水窃取），体毕归还游标头；`pendingAnnotations` 同模式 try/finally save/clear/restore 隔离，外层悬挂注解不被 lambda body 窃取。
- 分组内注释丢弃采用 **span 记录**（`groupingCommentSpans` + 各排水点跳过 span 内注释），避免游标前缀阻塞导致组内注释外泄（§5.4 登记差异）。
- 后缀链构建：Pratt 后缀循环独占单个 `ArrayList<AttributeStep>` 收集，链结束一次性冻结 `AttributeExpression`（消除每步不可变列表复制的 O(n²)）；token 消费顺序与 multiline push/pop 时序与 vendor 一致。
- 块起点回填：`layoutBlockStart` 将多行套件/类体块起点回填行尾空白，与旧路径块起点一致。
- `scan()` 续行以循环实现（JVM 无尾调用消除，防栈溢出）。

## 11. 已知限制与风险

已知限制（登记在案，见各引用条款）：

1. key-only 字典 pattern 丢 key + WARNING——AST 表达力缺口，未来随 AST 演进补全（§5.2）。
2. 表达式内部注释静默忽略（旧路径产出 `UnknownExpression` + WARNING）——parity 差异（§5.4）。
3. dedent 尾窗口混合缩进注释组归属差异（旧路径 scanner 启发式回填，上游 issue #85/#42 承认）——parity 差异（§5.4）。
4. 跨行分组 continuation 行跳闸的作用域归属错误（仅 >256 层病态嵌套 + 跨行分组）——§9。
5. 语义型 parse 检查暂缓（赋值目标类型、调用目标类型、隐式 `super()` 上下文）——留分析层（§5.6）。
6. 补全能力仍由 tree-sitter 路径独占，新路径补全方案待评估。

风险登记：

1. **D3 优先级分歧**：未来新增语料若无意引入分歧组合会被 parity 测试拦截，须按 §5.7 (iii) 登记。风险等级：低。
2. **Unicode/列换算**：Godot 列按字符、tree-sitter 列按字节；统一经 `SourceTextIndex` 换算，非 ASCII parity 用例已覆盖。风险等级：低。
3. **EOF 合成坐标**：Godot 在 EOF 合成 `NEWLINE` 时行号 +1，token end 行列可能落在非物理行；`Range` 一律以物理字节游标换算，合成 token 锚定物理 EOF，测试已覆盖。风险等级：低。
4. **恢复死循环**：所有恢复/同步循环有进度保证与 suite 空语句计数上限；错误语料测试带超时。风险等级：低。
5. **Godot master 漂移**：基线锁定 commit `c016f22`；升级走独立评估（§2）。风险等级：低。
6. **pattern 形状适配**：match pattern 在本项目 AST 中以 `Expression` 表达（`PatternBindingExpression` 等），与 Godot `PatternNode` 形态差异大；形状已按 tree-sitter 路径产出逐一钉死，key-only 字典 pattern 已登记信息丢失（§5.2）。风险等级：低。
7. **parity 过度承诺**：非法源与 §5.7 (ii) 类语法不做 AST 一致承诺，避免被 tree-sitter 容错形状绑架。风险等级：低。
8. **性能**：当前基线 ≈9.5 MB/s（§1.2），无回归阈值；如需阈值化可基于 fixtures 计时重测。风险等级：低。

## 12. 参考资料

- Godot 源码 vendor 副本（commit `c016f22009aa773f0b2e6fce72baec168746491f`）：`vendor/godot/gdscript_tokenizer.h`、`vendor/godot/gdscript_tokenizer.cpp`、`vendor/godot/gdscript_parser.h`、`vendor/godot/gdscript_parser.cpp`（上游路径：`modules/gdscript/`）
- 仓库文档：`doc/architecture-overview.md`、`doc/tree-sitter-ng-gdscript-implementation-plan.md`
- tree-sitter-gdscript：https://github.com/PrestonKnopp/tree-sitter-gdscript
