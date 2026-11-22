# GDParser 0.7.1：剩余缺口与补强方案

## 1. 当前基线、全局不变量与 `L-01`–`L-30` 全景状态

当前源码基线为 `master` 和 `origin/master` 的 `9024e36`。阶段 6 的出口记录显示，`clean build` 全量 25 个测试类、1886 项全部通过（其中 `GdCompletionContextTest` 1045 项，0 失败、0 错误、0 跳过）。本次是文档盘点，未重跑构建或改动实现。阶段 1–6 的设计、历史行号和执行记录以[归档](archive/gdparser-0.7.1-issues-fix-plan-stage1-6-archive.md)为准；本篇只描述基线行为、剩余缺口和待实施措施。基线测试通过不代表已覆盖下表的全部组合场景。

源码行号均指 `9024e36`。下文简写 `Parser`、`Tracker`、`Codes` 分别表示 `GdScriptParser.java`、`RecoveryTransactionTracker.java`、`DiagnosticCodes.java`，目录是 `src/main/java/dev/superice/gdparser/frontend/gdscript/`；同目录还有 `GdScriptParserFacade.java`、`GdTokenizer.java`、`BracketBalanceOracle.java`、`CompletionContext*.java` 和 `CompletionTokenStream.java`。测试简写 `P`、`E`、`V`、`T`、`F`、`C`、`Q`、`Ref` 分别表示 `GdScriptParserTest.java`、`GdScriptParserErrorRecoveryTest.java`、`GdScriptParserRangeInvariantTest.java`、`GdTokenizerTest.java`、`GdScriptParserFacadeTest.java`、`GdCompletionContextTest.java`、`GdParserParityTest.java`、`GdParityReferenceTest.java`，均在 `src/test/java/dev/superice/gdparser/frontend/gdscript/`。

语言对照使用 [`c016f22009aa773f0b2e6fce72baec168746491f`](https://github.com/godotengine/godot/tree/c016f22009aa773f0b2e6fce72baec168746491f) 的本地 `vendor/godot` 快照。纯 Java 恢复可有意强于 Godot；保留 AST 不等于语法合法或可编译。旧 tree-sitter parity 只用于有约定的兼容行为，不裁定语言合法性。

### 1.1 全局不变量

| 编号 | 约束 |
|---|---|
| `I1` | 数字 `LiteralExpression` 不带前导符号；前缀符号保留为 `UnaryExpression`，中缀 `+`/`-` 仍是二元运算。 |
| `I2` | 恢复步骤消费 token、推进有限状态或退出帧；预判至多单遍，列表前瞻、诊断回退和熔断配平须可摊销为线性工作。禁止逐层重扫余段或逐 token 重扫整行。 |
| `I3` | AST 范围为 UTF-8 字节半开区间，`parent.startByte <= child.startByte && child.endByte <= parent.endByte`。AST 点位为零基 UTF-8 列；公开 token 行列为一基、按 code point 计数。诊断范围必须位于源码内，但不必落在宿主 AST 范围内；`MISSING` 不消费源码，`ERROR` marker 覆盖已归属的坏片段。 |
| `I4` | 正常、EOF、错闭合、深度/套件熔断及重入退出后，结构帧、多行模式、缩进三栈回到基线；不遗留回放、缩进快照或待归属子帧。 |
| `I5` | 纯 Java 每条 `ERROR` 有非空的注册 `code`；若有合理的存活构造宿主，`RecoveryLink` 的 owner 按同次解析 AST 对象身份可达，且 `code/owner/facet` 配对已注册。只有明确登记的独立词法错误或确无宿主的顶层孤立垃圾可空；诊断 code 允许独立不等于在任意语法位置都可空。旧 CST 路径不纳入这条门禁。§2.3 列出了基线尚未满足的路径。 |

### 1.2 状态总表

38 个 `L` 子项中，22 项已修复，16 项部分修复；另有原 3、5a、5c 已修复，原 5b 部分修复。已修复项仍可能欠更精确的行为断言。`FC` 列列主项，带 `+` 的列关键协同项；`RG` 列指本轮主归属，独立的 GDCC 联调统一在 `RG-7`，不计入本仓库修复数。

| 项目 | 原 `FC` | 本仓库状态 | 基线行为单测与缺口 | 新 `RG` |
|---|---|---|---|---|
| `L-01` 紧贴数字的前缀符号 | `FC-7` | 已修复 | 有幂与前缀形态对照；补 token/AST 联合门禁 | `RG-6` |
| `L-02` 纯 trivia | `FC-1` + `FC-6` | 已修复 | 有空文件、注释、region 与仅注释套件；补 strict 的 CRLF 矩阵 | `RG-6` |
| `L-03` 错闭合与真实 token | `FC-1` + `FC-2/8` | 已修复 | 有独立/托管模式；补坐标和所指 opener 的联合断言 | `RG-6` |
| `L-04` `return` 坏值槽 | `FC-3` + `FC-2/6` | 已修复 | 有裸返回与坏值对照；补 `return )` 和 marker 精确范围 | `RG-6` |
| `L-05` 属性访问器排空 | `FC-5` + `FC-2/6` | 部分修复 | 有重复/未知项；缺混风格重复项单主诊断 | `RG-4` |
| `L-06` `match` 绑定及 rest | `FC-3` + `FC-2/6` | 已修复 | 有顶层和容器模式；补范围、配对及无级联矩阵 | `RG-6` |
| `L-07` 空键空值字典项 | `FC-3` + `FC-4/6` | 部分修复 | 双 marker、相邻项已覆盖；组合覆盖与 `EnclosedDamage`（`1B`）待实施 | `RG-1` |
| `L-08` 内嵌非法赋值 | `FC-3` + `FC-2/6` | 部分修复 | 直接值槽与左右子树已覆盖；容器组合、`EnclosedDamage`（`1B`）及 Lua 键对照待实施 | `RG-1` |
| `L-09` 恢复中缀及坏类型 | `FC-3` + `FC-4/6` | 部分修复 | 缺类型、`?`、缺 `else` 已有；缺非空坏类型传播 | `RG-1` |
| `L-10` 语句尾垃圾归属 | `FC-6` + `FC-2` | 部分修复 | 普通尾垃圾已绑语句；孤立 closer 仅检查 code | `RG-3` |
| `L-11` key-only 字典模式 | `FC-3` + `FC-6` | 已修复 | 纯 Java、旧 mapper、参考、serde 已有；strict 过滤用合成 WARNING | `RG-6` |
| `L-12` 注解作用域与悬空 | `FC-7` + `FC-5/6` | 部分修复 | 有悬空/错级别/访问器测试；缺独立指令夹在待绑定注解中 | `RG-5` |
| `L-13` `extends` 首写优先 | `FC-7` + `FC-6` | 部分修复 | 有头部与体首重复；缺嵌套类体首连续两个 `extends` | `RG-5` |
| `L-14` 必选类型槽 | `FC-7` + `FC-3/6` | 已修复 | 有上下文码和坏类型去重；补单形参、范围和边界对照 | `RG-6` |
| `L-15` 混字典风格 | `FC-4` + `FC-6` | 已修复 | 有双向风格冲突；补缺值、闭合符和后项联合断言 | `RG-6` |
| `L-16` 缺分隔符字典键 | `FC-4` + `FC-3/6` | 部分修复 | 有 `{1 2}` 与跨行独立键；缺跨行分组键闭合同行的值 | `RG-4` |
| `L-17` 分组保留自己的 `)` | `FC-2` + `FC-3/6` | 已修复 | 有外层实参、EOF 对照；旧方法名语义反向，补 marker 范围 | `RG-6` |
| `L-18` lambda 损伤同步隔离 | `FC-2` + `FC-6` | 部分修复 | 有健康 lambda 和错闭合替换；缺同步中遗留局部 opener 的组合 | `RG-2` |
| `L-19` 注解与失败预算 | `FC-5` + `FC-7` | 已修复 | 有合法前缀与失败跨阈值；补行为命名、尾部悬空归属 | `RG-6` |
| `L-20` `for` 坏类型与 `in` | `FC-7` + `FC-2/6` | 部分修复 | 类型返回 `null` 已同步；缺非空坏类型与 `in` 同行 | `RG-1` |
| `L-21` 受损字典模式恢复 | `FC-3` + `FC-4/2/6` | 已修复 | 有真实/缺失 `}` 两类；补边界的精确角色、范围及归属门禁 | `RG-6` |
| `L-22` 受损表达式续折 | `FC-4` + `FC-2/6` | 已修复 | 有分组、下标、调用和 `super`；清理反义名称并补独立回退 | `RG-6` |
| `L-23` lambda 泛型逗号 | `FC-2` + `FC-6/8` | 部分修复 | 有简单坏泛型让出外层逗号；缺未闭嵌套 `Dictionary` 逗号区分 | `RG-2` |
| `L-24` 签名或套件接管 | `FC-5` + `FC-2/6` | 部分修复 | 有冒号让出与合法续写；缺同一位置的深缩进 `->` | `RG-4` |
| `L-25a` 列表漏逗号 | `FC-4` + `FC-2/6` | 已修复 | 调用、数组、形参、信号及硬边界已有；补 code/坐标联合检查 | `RG-6` |
| `L-25b` `match` 分支漏冒号 | `FC-5` + `FC-2/6` | 已修复 | 有缺体和有体、下一分支及 `match.section`；补精确锚点组合 | `RG-6` |
| `L-25c` 坏构造的名称/初值 | `FC-5` + `FC-2/6` | 已修复 | 有四类构造与健康体；补 marker/诊断范围组合，符号注册属下游 | `RG-6` |
| `L-26` 深度熔断配平 | `FC-2` + `FC-6` | 部分修复 | 有超深及健康后续；缺未入栈层中的异类 closer | `RG-2` |
| `L-27a` 公开分词布局 | `FC-1` | 已修复 | 有托管/独立模式；补同源解析和补全的布局对照 | `RG-6` |
| `L-27b` token/EOF 物理坐标 | `FC-1` | 已修复 | CRLF、emoji、错误、长行分项已有；补组合坐标和有效字节断言 | `RG-6` |
| `L-28.1` 未闭类型续写 | `FC-8` + `FC-1/2` | 已修复 | 两入口续写及无 `]` strict 拒绝已有；缺带真实 `]` 的硬换行 strict 拒绝 | `RG-6` |
| `L-28.2` 八类空槽 | `FC-8` + `FC-1/2` | 已修复 | 有双入口和零宽门禁；缺普通 `x = target(\|)` 的双入口 CRLF 组合 | `RG-6` |
| `L-28.3` 健康输入全覆盖 | `FC-8` | 已修复 | 健康/恢复样本流已分开；保持无排序截断，分开校验诊断和存活节点 | `RG-6` |
| `L-29` 稳定诊断与身份归属 | `FC-6` | 部分修复 | 基础门禁已有，缺位置判定；**一处旧缺陷锁：`E:1368–1383` 的 `assertNull`** | `RG-3` |
| `L-30a` 左值和直接调用 | `FC-7` + `FC-3/6` | 部分修复 | 有表面形态与缺名去重；缺损伤中缀用于赋值目标的去重 | `RG-5` |
| `L-30b` 带空白推断 | `FC-7` | 已修复 | 空格/tab、常量及形参已有；保留 exact text、范围与 parity | `RG-6` |
| `L-30c` 套件裸 lambda | `FC-7` + `FC-3/6` | 部分修复 | 有直接 `func` 与旧路径分歧；缺分组后仍为裸 lambda 的语句 | `RG-5` |
| `L-30d` `_static_init` | `FC-7` + `FC-6` | 已修复 | 有形态、签名和同名 lambda；补普通/变长参数独立负例及归属 | `RG-6` |
| 原 3 跨行未闭容器 | `FC-2` + `FC-1/6` | 已修复 | 有连续声明和重复喂入；补硬边界/真实配对对照 | `RG-6` |
| 原 5a 错闭合诊断 | `FC-2` + `FC-1/6` | 已修复 | 有祖先优先和单层替换；补精确诊断与物理坐标交叉 | `RG-6` |
| 原 5b 错闭合后保护下一句 | `FC-2` + `FC-1/6` | 部分修复 | 常规后继已有；与 `L-18` 共用组合缺口 | `RG-2` |
| 原 5c 补全帧不泄漏 | `FC-8` + `FC-1/2` | 已修复 | 有 LF 双入口和 exact partners；缺两条经典错闭合的 CRLF+emoji 分类组合 | `RG-6` |

## 2. `9024e36` 根因详单

以下 `\n`、`\r\n`、`\t` 为源码字符转义，`|` 表示补全光标，不是源码。部分修复项的“现状”依据基线代码路径和现有测试核对；未新增动态复现。本篇所列缺测输入须在相应实施阶段首先加为定向行为测试。

### 2.1 `RG-1`：容器吸收与非空坏类型截断损伤传播

| 项目与精确输入 | 基线行为及根因 | 目标与未覆盖的断言 |
|---|---|---|
| `L-07`：`var d = {:}\n`、`var d = {"a": 1, :, "b": 2}\n` | `Parser:9242–9261` 已保留冒号见证的条目和 `dictionary.key`、`dictionary.value` 两个 `MISSING`。`Parser:9275–9276` 将字典作为吸收宿主返回 `Ok`，掩盖了“本层闭合完整但子树含损伤”的状态。 | `E:1237–1271` 已覆盖结构。按 §3 决策 `1B` 在 `R1.1` 为 `ParseOutcome` 新增第 5 态 `EnclosedDamage`（本层结构完整但含子损伤），字典与数组在内部含损时返回 `EnclosedDamage` 而非伪装 `Ok`；补两个精确零宽位置、各诊断 code/owner/facet 和前后兄弟；`{}`、`{"a":1,}` 不增伪条目。 |
| `L-08`：`g([a=1], 2)`、`{1: a=1}`（放入变量初始化或实参） | `Parser:9657–9685` 已返回 `Damaged(AssignmentExpression)`，保留左右并发带归属的 `ASSIGNMENT_DISALLOWED_IN_EXPRESSION`。数组 `Parser:9036–9085` 和字典 `Parser:9275–9276` 吸收后返回 `Ok`；同时 `Parser:9188–9202` 的 Lua 键校验对 `{foo(1+) = 2}`（`Damaged`）漏报 `DICTIONARY_INVALID_LUA_KEY`，对 `{[1+] = 2}`（`Ok`）则正常报错。`E.assignmentInsideCallArgument:408–437`、`E.nestedAssignmentDegradationKeepsSingleDiagnostic:1909–1950` 保证直接值槽保留节点和 `ERROR`。 | 按 §3 决策 `1B` 在 `R1.1` 引入 `EnclosedDamage`：数组、字典、已闭合调用/下标在子树受损时返回 `EnclosedDamage`，既保持 D6 吸收与外层续折（如 `foo([1+]).bar`），又让表面形态守门（如 Lua 键 `{[1+] = 2}`、`{foo(1+) = 2}`）识别完整非合法键，同时对本层残缺的 `{(1+) = 2}`（`Damaged`）继续避免二次报错；补公开入口 AST、外层实参 `2`、可达诊断与 strict 拒绝。 |
| `L-09`：`x as Node.`、`x as Array[Array[int]]`、`x is Array[int`（表达式位置） | `parseTypeInternalImpl(boolean)` 返回 `ParsedType` 而非直接 `TypeRef`。`Parser:7243–7245` 对嵌套容器报错后继续，`:7259–7264` 忽略 `consumeCloser` 结果，`:7267–7273` 消费 `.` 后缺内层名仍可给 `Node` 的 `TypeRef`，范围不含 `.`。`:7157–7160` 解包成普通 `TypeRef`，且 7 处调用点均靠 `diagnosticCount() == diagBefore` 区分未写与已报错失败。`parseCast`、`parseTypeTest` 分别在 `:9597`、`:9626` 以非空结果直接建 `Ok`。 | 按 §3 决策 `2B` 在 `R1.2` 将 `parseTypeInternal` 统一返回 `ParseOutcome<ParsedType>`，消除 7 处 `diagBefore` 计数判断；三例保留左操作数、坏类型实际消费范围（含 `.` 或已读泛型）和原发诊断，不再伪装 `Ok`（并核实 `as`/`is` 无活跃帧时的 `MISSING_CLOSER_TYPE_BRACKET` 发射安全）。保留 `x as int`、`x is Array[int]` 零诊断健康 AST；保留 `x as`、`x is not`、`Array[]` 单主诊断、恢复 marker 和 serde（`E:1953–2029,2172`）。 |
| `L-20`：`func f():\n\tfor i: Foo. ??? in xs:\n\t\tpass\n`；将类型替换为 `Array[int` 再跑 | `Parser:5652–5702` 已记录 `sawColon`，但只对 `iteratorType == null` 做有界同行同步。场景 `Foo. ???`、`Array[int ???` 可带非空坏类型绕过同步，当前 token 不是 `in` 时走 `FOR_MISSING_IN`（`parse.for.missing_in`），丢迭代源和循环体。`P:3266–3281` 仅证明空结果路径。 | 借助 `R1.2` 的 `ParseOutcome<ParsedType>`（`Missing`/`Failed`/`Damaged`/`EnclosedDamage` 均触发同行有界同步），两个组合保住 `xs`、体内 `pass`、外层兄弟和 `for.iterator` 原发类型诊断，不报假缺 `in`。真的缺 `in` 时有冒号只提示 `in`，无冒号仍可提示 `in` 或 `:`；不得越过实际块冒号、同行末尾或祖先边界。 |

### 2.2 `RG-2`：局部同步、泛型逗号与深度熔断错闭合

- **`L-18`／原 5b。** `func f():\n\tfoo(1+, func(): = [1)\n\tpass\nvar after = 1\n`。`synchronize()` 在 `Parser:3284–3301` 要求括号局部计数全归零才询问祖先逗号/closer；`:3318–3334` 对各类括号独立加减，遇 `)` 不撤销同步时留下的 `[`。这个组合会让 `)` 被同步消费，外层误报 `parse.missing_closer.call`，并丢 `pass` 和 `after`。预期把 `)` 交给外层 `foo`，保住调用、两个作用域中的后继节点，只保留有因果关系的坏实参/坏体诊断。`E:1488–1490,1823–1827,2751` 的正常 lambda、已有损伤及替换 closer 是回归对照，不等于测过局部遗留 opener。
- **`L-23`。** `var x = g(func() -> Dictionary[String, Array[\n,\n\tnode.\n\tpa\n)\n` 当前把首个逗号交给 `g`，随后把未闭 `Array[` 解析为外层 `SubscriptExpression`，形成三个实参和次生下标错。在有祖先逗号所有者时，`shouldContinueDictionaryTypeComma` 只接受 `BracketBalanceOracle` 已精确配对、且 closer 位于逗号之后的 `Dictionary`（`Parser:7181–7192`）。若走类型元素路径，嵌套容器本来就会报错（`:7243–7245`）；达参数上限或该容器不允许逗号时，`:7249–7252` 已退回 `NONE` 并跳出，不会到 `:7256` 再判断逗号。不能只放宽该辅助方法，也不能把坏嵌套改成合法类型。
  - 补全分类已锁定：`C:477` 的 `var x = g(func() -> Dictionary[String, Array[\n,\n\tnode.\n\tpa|\n)` 由 `C.matchPatternsAndDamagedTypesDoNotLeakFrames:461–464` 在 facade 和空 AST 两入口期望 `MEMBER_ACCESS`，替换 `pa`，receiver 为 `node`。换行后的外层逗号已让未闭的 `Role.TYPE` 退出，`node.pa` 不是跨行类型续写；`TYPE_POSITION` 必须满足 `receiverRange == null`（`CompletionContext.java:17–47`）。
  - 按 §3 决策 `3A`（保持未配对 `Dictionary` 让逗号给外层的既定规则，不修改 `shouldContinueDictionaryTypeComma` 的坏逗号优先级）：对跨行坏嵌套只锁非法嵌套/缺闭合严格报错、无体 lambda 与 `node.pa` 存活且补全保持 `MEMBER_ACCESS`，不因该残缺样本改动外层逗号归属策略。
  - 配对对照 `var x = g(func() -> Dictionary[String, Array[i])` 和把 `Array[i]` 换成裸标识符 `other` 的同行用例：恢复 AST 必须保留两种真实第二实参；在 `Ar|ray[i]` 和 `oth|er` 处，两入口给外层 `IDENTIFIER`，不给 `TYPE_POSITION`。`C.trustedTypesDistinguishOpenCaretSlotsAndCommaOwners:1197–1200` 的数据（`C:1214–1222`）已有未闭字典 `oth|er` 对照；跨行坏例和两种未闭字典对照都必须 `parseStrict` 拒绝。合法 `Dictionary[String, int]` 的内部逗号另作零诊断对照。
- **`L-26`。** 在 `match` 分支头拼 `"[".repeat(260) + "1}" + "]".repeat(259) + ":"`，带有体、后续 `_:`、函数内 `local_after` 和顶层 `after`；再用 `([1})` 变体并接 260 个 `]` 验证祖先 closer 优先。深度预算为 256（`Parser:254,3352–3375`）。`unwindPoisonedBracketChain` 在 `Parser:2742–2760` 找不到同类未入栈 opener 时清空整条链，剩余真 `]` 越过应有作用域，产生派生 `parse.missing_separator.match_colon`。修复应优先交给真祖先，否则只替换最内一层；不把预算加大来掩盖。`E:742,781,2456,2540` 有常规深度熔断，组合缺错配插入、精确诊断和线性步数。

### 2.3 `RG-3`：归属链断开、语句尾绕行与位置门禁

- **`L-10`。** 套件内同一行的 `pass )`、`return 1 )` 以 `Parser:3571–3575` 消费 `)` 并转 `:1160–1167`，产生 standalone `UNEXPECTED_CLOSER`（`parse.unexpected_closer`），绕开原语句的 `statement.trailing`（`:3524–3529,3660–3670`）。`Codes:267–282,449–457` 已允许该 code 与 `statement.trailing`、`suite.body`、`class.body` 配对，不需要新增配对。`finishStatementProvisional`（`Parser:3550–3575`）可先越过 `NEWLINE` 再消费下一行的 `)`；不能据消费者把它归到上一条健康 `pass`。只诊断真实垃圾字节：同一行报给原语句，下一行孤立 closer 报给存活套件宿主（`suite.body`），不扩张健康语句原范围。祖先拥有的 closer 不走尾部或容器垃圾恢复；真正无宿主的顶层 closer 保持 null。既有 `E:2033–2107` 测普通尾垃圾归属，`E:2799–2803` 的 `pass]` 只锁 code。
- **`L-29.1`，丢弃的参数默认值。** `func f(x, x = func(): return (1`。重复参数默认值被丢弃后，内层 lambda 套件的归属授权没有连到仍存活的签名（`Parser:3017–3040,8489–8496,8668–8671`），终结器找不到合法 owner/facet 时保留诊断、清空链接（`Tracker:614–631`）。**`GdScriptParserErrorRecoveryTest.recoveryLinkOfDiscardedOwnerIsSwept`（`E:1368–1383`）用 `assertNull` 锁住这个残余缺陷。** 改为断言仅一个参数保留、分组缺 `)` 的原 code 和 EOF 零宽范围不变，并通过显式授权连到同代存活签名的合法 facet，不允许把已丢弃的 lambda 当 owner。
- **`L-29.2`，存活类体与套件。** `class Outer:\n\tclass Inner:\n\t\tstatic\n\t\tpass\n`，以及将 `static` 替换成 `% ^ &` 的变体：`Parser:3999–4005,4086–4113,2955–2962` 的 legacy 发射不经过 owner 决策（`Tracker:374–389`），即使 `Inner` 存活也可无链接。`func f():\n\t)\n\tpass\n`（`Parser:5448–5450`）和 `class C:\n\t)\n\tpass\n`（`:4086–4090`）在第一条语句前遇孤立 `)` 时也无链接。前者应绑定 `f` 的 `suite.body`，后者应绑定 `C` 的 `class.body`；嵌套类中的垃圾绑定 `Inner` 而非外层类。`func f():\n\tpass\n\t)\n\tpass\n` 的孤立 closer 即使被上条语句的收尾消费，也只能绑套件而非 `pass`。范围锚实际 token，保留健康后继，区分真正顶层孤立例外；顺带核实 legacy 回退的其他路径是否可达，不做笼统改绑。
  - 另有两条类体路径：`class C:\n\tfunc 1\n\tpass\n` 在建立函数签名 frame 前拒绝无效函数名（`Parser:8334–8337`）；`class C:\n\t"doc" var a = 1\n\tpass\n` 在 `notStandaloneDocstring()` 中报告说明字符串后缺换行（`Parser:3913–3918`）。两例的 `parse.syntax.unexpected_token` 在基线均无链接；应连到同次解析仍存活的 `C`，facet 为 `class.body`，且后续 `pass` 仍留在 `C` 中。
- **`L-29.3`，套件熔断。** 构造有宿主的函数或 lambda，用连续 105 次真实语句失败触发预算，再接同级或外层健康后继。`Parser:4463–4470,4483–4490` 强制 standalone；实际常量为 `STATEMENT_TOO_MANY_ERRORS`（`parse.statement.too_many_errors`）。按 §3 决策 `4B-A`，在 `Codes:458` 开放 `Set.of("suite.body")`，有存活套件宿主时归属该宿主，无宿主时保持 `null`，断言单个熔断诊断、存活结构和归属；不要通过解绑整批诊断来绕过门禁。
- **`L-29.4`，门禁位置。** `V:1132–1141` 校验已连 owner 的身份和配对，但空链接只按 `allowsNullRecovery(code)` 放行。增加同 code 的顶层孤立正例、存活套件/类内负例、确属独立词法错误对照，以及多次独立解析的同代身份检查。具体允许位置来自基线语法和发射上下文，不能只凭范围落入某 AST 包络猜 owner。

### 2.4 `RG-4`：块头与容器边界启发式、混风格重复诊断

- **`L-05`。** `var p:\n\tget = g,\n\tget:\n\t\tpass\nvar after = 1\n`。`Parser:7596–7615` 关于重复 getter 与风格不符的判断相互独立，可在同一坏项发 `PROPERTY_DUPLICATE_GETTER` 和 `PROPERTY_INVALID_ACCESSOR`；反向混写在 `:7784–7787` 报风格错。`E:3055–3131,3958` 已锁常规重复及健康后继。两方向增加每坏项一个主诊断、`variable.property` 身份、词首范围、坏访问器体排空及 `after` 存活；补 setter 对照。只有风格错而未重复时仍须报告风格错。
- **`L-16`。** `var d = {(\n\ta) 2}\n`。`Parser:9134–9135` 捕获键起始行，`:9216–9238` 用它判断能否接值；`2` 同于分组键的结束行但异于起始行，被拆成另一缺分隔符条目（两个 `MISSING` 值、三条诊断）。预期一个恢复条目，只报一次 `MISSING_SEPARATOR_DICTIONARY`（`parse.missing_separator.dictionary_separator`）。按 `Parser:9250–9255` 的范围合同，`DictEntry.range()` 从 `(` 的字节 9 开始，覆盖分组括号与恢复值 `2`；解包后的 `key.range()` 仍仅覆盖 `a` 的 `[12,13)`。`P:1402–1450`、`E:2944–2964,3484` 是已有对照；`{"a"\n\t"b":1}` 的后置键值和独立缺逗号诊断仍保留。
- **`L-24`。** `func f(a:\n\t-> int: pass\nvar after = 1\n`。`shouldYieldColonToEnclosingSuite`（`Parser:7901–7944`）在 `:7936–7943` 检查缩进和未闭性，却不区分 `->` 签名续词和可信套件。仅改让出冒号的判断还不够：`consumeCloser` 退出未闭形参多行模式时可回放 `NEWLINE`/`INDENT`（`Parser:2916–2921,1425–1458`），`parseFunctionSignature` 却在 `:8538–8543` 紧接着匹配 `FORWARD_ARROW`。预期只在缺 `)` 的受损头部识别可信箭头，保留参数、返回类型 `int`、实际套件冒号、体内 `pass` 和顶层 `after`；仍报告缺 `)`。负对照 `func f(a):\n\t-> int: pass\n` 已有完整形参和套件冒号，不能把套件内箭头当成返回类型；另保留正常 `pass` 套件、合法多行类型、无体函数和同行 `->`。

### 2.5 `RG-5`：前端语法守门与 Godot 对齐

- **`L-12`。** 设四行为 `@warning_ignore("unused_variable")\n@warning_ignore_start("unreachable_code")\nvar a = 1\n@warning_ignore_restore("unreachable_code")\n`。三个失败夹具：加 `class C:\n` 且四行各缩进一层；加不缩进的 `var prefix = 1\n` 再接这四行；加 `func f():\n` 且四行各缩进一层。未加前缀的四行在文件开头是干净对照，走 `Parser:3700–3715`，不是类成员区复现。失败分支在 `Parser:4019–4026,5229,7081–7105` 遇独立指令提前 drain，误报 `parse.annotation.dangling`。Godot 将独立指令即时处理、待绑定注解另入栈（`vendor/godot/gdscript_parser.cpp:1166–1181,2141–2154`）。恢复应保持同作用域的源序和目标合法性：合法目标无假悬空，错目标、真实悬空/无效指令、访问器越界仍按各自规则诊断；用源序及校验结果证明绑定语义，不给扁平注解 AST 增加 target 字段。
- **`L-13`。** `class C:\n\textends A\n\textends B\n\tpass\n`，再放入外层类。`Parser:4242–4257` 只尝试一次体首 `extends`，第二条落入默认类体分派 `:4061,4086–4113`，得到通用 `parse.syntax.unexpected_token`。应与表头重复路径统一：第一个关键字占槽（即使首目标坏），第二个报 `CLASS_DUPLICATE_EXTENDS`（`parse.class.duplicate_extends`），owner 是 `C`、facet 为 `class.extends`，不发布 `B` 为有效父类，后续节点照常处理。补体首连续两条及坏首目标；保留 `P:2123,2129` 两方向和作用域相互独立的对照。
- **`L-30a`。** `(a ?) = 2` 已有 `parse.syntax.unexpected_token`，又报派生 `ASSIGNMENT_INVALID_TARGET`（`parse.assignment.invalid_target`）。`Parser:9657–9686,9698–9704` 赋值 marker 守门没包含 `RecoveredInfixExpression`；`(a ?)(2)` 的直接调用守门已在 `:9808–9824,9916–9922` 识别它。目标：保留坏左值及 RHS `2`，只发原发诊断；健康但不合法的 `1 = 2`、`f() = 2`、`f(1)(2)` 仍各有严格守门诊断，`(x) = 2`、`f()[0] = 2`、`(f)(1)` 不受影响。上游赋值见 `vendor/godot/gdscript_parser.cpp:3137–3139`，直接调用见 `vendor/godot/gdscript_parser.cpp:3450,3495–3516`；`E:944–983` 与 `Q:155–166` 仍需新增这组损伤去重断言。
- **`L-30c`。** `func f():\n\t(func(): pass)\n\tpass\n` 和具名多行分组版 `func f():\n\t(func named():\n\t\tpass\n\t)\n\tpass\n`。健康分组解包为内节点（`Parser:8990–9034`），通用语句路径（`:5411–5429`）生成 `ExpressionStatement(LambdaExpression)` 而没有裸 lambda 诊断，和直接 `func` 分支（`:5393–5410`）不一致。Godot `vendor/godot/gdscript_parser.cpp:3358–3366,2159–2201` 也解包分组，并在 `DEBUG_ENABLED` 下对裸 lambda 报错。Java 延续既定各构建均拒绝裸 lambda 的规则：同一稳定 `LAMBDA_BARE_STATEMENT`，范围覆盖正确主体，关联存活语句，后续 `pass` 保留。`(func(): pass).call()`、赋值和实参 lambda 仍零诊断；类体匿名函数走独立的缺名路径。`P:2926–2958`、`Q:327–352` 仅锁直接 `func` 场景。

### 2.6 `RG-6`：已修复行为的覆盖补强

下表的已有入口只列测试方法，范围从声明到结束大括号；测试数据与辅助方法另标。不把已有样本列作未覆盖，也不为补覆盖修改公开契约。位置敏感的组合同时测试 LF、CRLF，至少一例在同一行目标前放 emoji，用独立 UTF-8 字节长度验证 `Range`。对 `MISSING` 分别断言起止都等于预期锚点；对 `ERROR` 断言完整片段，而不是一律要求零宽。

| 已修复项 | 已有行为测试（基线） | 待补或独立锁定的行为 |
|---|---|---|
| `L-01` | `P.unaryMinusShape:1024–1031`、`P.powerIsLeftAssociativeLikeGodot:1045–1052`、`P.tightNumericSignBindsLikeGodotAndSpacedSignUsesPrecSign:1055–1203` | 用同一组紧贴/空格、十六/二进制、`.5`、`1-2`、分组/调用后的减号、`is` 和链式幂同时锁 token 无符号、AST 运算形态和零诊断；不放宽 `2.5.abs()` 的词法规则。 |
| `L-02` | `P.emptyAndWhitespaceOnlyFiles:160–171`、`P.commentAndRegionOnlyFilesParseCleanlyAndPreserveTriviaNodes:174–197`、`P.commentOnlyFunctionAndClassBodiesStillReportMissingIndentedBlockAndKeepSiblings:284–298` | 空串、LF/CRLF、纯空白、含 emoji 注释/region、无尾换行分别测 `parseStrict`、trivia 与物理范围；仅注释套件仍报缺体，外层兄弟保留。 |
| `L-03`、原 5a | `T.closingBracketsMustMatchOpeners:362–415`、`T.managedTokenizerAlwaysEmitsRealCloserTokensWithoutLexicalCloserErrors:418–445`、`F.tokenizeSuppressesBracketIndentationErrorsWhileKeepingOuterIndentationAndCloserErrors:124–155`、`E.nestedUnclosedPrintAndCloserReplacementFollowOwnershipRules:2352–2382` | 同组测 `[)`、`([)]`、`([{)`、空栈 closer 和健康嵌套。公开 tokenizer 锁 `ERROR` 片段和坐标；托管模式保留真实 token；解析区分 `foo([1)` 祖先优先和 `[1)` 单层替换，锁原 opener、精确诊断和身份配对。 |
| `L-04` | `E.returnDamagedValueKeepsMarker:1004–1017`、`E.returnSlotControls:1020–1088` | `return =3`、`return )` 锁实际坏片段及 `return.value`；`return 1+` 缺右值是零宽；对照裸 `return`、lambda 中逗号前裸返回、`return 1 2` 的语句尾归属。孤立 closer 的检查与 `R3` 联验。 |
| `L-06` | `E.matchPatternMissingBindNameAndRestErrorsPreservePositions:1645–1704`、`E.matchPatternMarkerInFollowUpBranchStaysInsideSection:1707–1721`、`P.matchRestPatternMisuseErrors:2765–2831` | `var :`、`..:`、`[1, var , 3]`、`{"a": var }`、`{"k": ..}`、`{.., "k": 1}` 的好/坏槽位置、被消费片段、可达的 `match.pattern`，不新增“无模式”级联。 |
| `L-11` | `F.parseStrictSucceedsWithZeroDiagnosticsForKeyOnlyDictionaryPattern:91–96`、`P.matchKeyOnlyDictionaryPatternPreservesKeyWithNullValue:2694–2740`、`Ref.keyOnlyDictionaryPatternPreservesKeyWithNullValueAndNoDiagnostics:255–267`、`CstToAstMapperTest.keyOnlyDictionaryMatchPatternsPreserveKeysWithZeroDiagnostics:236–293`、`AstSexprSerdeTest.shouldRoundTripKeyOnlyDictEntryAndRecoveredInfixExpressionInBothFormats:231–283` | key-only 对 `"name"`、`CONST_KEY`、`1 + 2` 锁零诊断、`DictEntry.value() == null`、children/walker 省略值和两种 serde；与值字面量不得变成 null 分开。纯 Java 尚无生产 WARNING；合成结果的过滤测试见 §3，不当作解析器端到端告警。 |
| `L-14` | `E.requiredExplicitTypeSlotsReportContextualCodesWithoutDoubleReportingDamagedTypes:4641–4733` | 增补单个 `func f(x:): pass`、signal、lambda、变量、`const X:` 的 code/owner/facet 及缺槽锚点；`Array[]` 或不许可 `void` 无二报，`)`/`,`/`;` 不被吞，`: =` 及两种属性写法零误报。 |
| `L-15` | `P.mixedDictionaryStylesAreRejected:1369–1399`、`E.phase4DictionaryStateMachineCrossLineAndNestedIsolation:2944–3002` | 两方向混风格分别锁分隔符只消费一次、有效值 `2`、缺值 `MISSING`、仍有真正 `}` 与健康后项；同一录入不多发次生错。 |
| `L-17` | `E.groupingPureMissingDoesNotConsumeCloser:1873–1906` | 更名反映 `()` 消费属于自己的 `)`，不改坏输入为合法。`foo((), 1)` 实参 2 保留；`(1+)` 正确结束，`(\n` EOF 保留单主诊断。补分组 marker 独立零宽/已消费范围及精确 code。 |
| `L-19` | `P.circuitBreakerCountsAnnotatedFailuresLikeGodot:2224–2243`、`E.phase4PrefixAnnotationProgressAndDanglingSeparation:3252–3267` | 更名描述合法注解前缀不占失败额度、真实失败仍熔断；保留 120 条合法前缀、同一行合法前缀和跨阈值失败；补尾部悬空的 owner/facet 与顺序，不把套件熔断改成注解计数。 |
| `L-21` | `E.dictionaryPatternMixedEntriesFallBackTogether:1584–1599`、`E.dictionaryPatternNoCommaTailStopsLoopAndReportsCloser:1602–1642`、`E.phase4MissingCommaSyncAcrossListsAndNegativeProtection:3005–3052` | `{1+, 2: 3}`、`{1+ var x: 3, 2: 3}` 已有结构测试；补真实 `}`、缺 `}` 的精确锚点/归属及可达性，分支冒号、`pass` 与 `after` 分属正确作用域。模式 key-only 和表达式字典不混用。 |
| `L-22` | `E.damagedSubscriptOrGroupingDoesNotFoldFollowingOperators:1740–1764`、`E.damagedArgumentsStopTheSuffixChain:1791–1820`、`E.superCallWithDamagedArgumentStopsFold:1855–1870`、`E.phase4DamagedContinuationAndNegativeGuards:2909–2941`、`E.endStatementDrainsSameLineResidueWithoutStealingSemicolonOrOuterComma:3427–3447`、`P.damagedSuperInsideLambdaArgumentStopsFold:1660–1690`、`C.recoveryDrivenContexts:168–170`（仅 facade） | 将反义方法名改成实际行为（受损节点保留但可受控续折）。锁 `a[1+]+2`、`(1+)+2`、受损调用与 `super`，补精确 `super. var y = 1` 及 `f(func(): super., 1)` 对照、`self.b.(1)` 不误绑为具名调用。`super.`、`super.ba` 等补全传空 AST 独立验分类和范围，不借 parser 丢句模拟，也不承诺候选。 |
| `L-25a` | `P.callArgumentRecovery:1272–1286`、`E.phase4MissingCommaSyncAcrossListsAndNegativeProtection:3005–3052` | 调用 `foo(1 2)`、数组、函数/信号参数漏逗号锁后项、单个 `MISSING_SEPARATOR_COMMA`、范围及合法宿主；CRLF/emoji 对照；无匹配 opener 的跨行 `print` 保住后句、不报假漏逗号。 |
| `L-25b` | `E.phase4MatchSectionMissingColonWithBodyAndSibling:3159–3197`、`E.matchSectionMissingColonAfterLambdaGuardPreservesNextBranch:3402–3424`、`E.matchSectionMissingColonAlwaysAdvancesAndKeepsFollowingBranches:4356–4374` | 锁首分支缺 `:` 的插入范围、`MISSING_SEPARATOR_MATCH_COLON` 与 `match.section`；有体、无体、lambda guard 对照，后续 `2:`、父级 `DEDENT` 和顶层 `after` 不被接管。 |
| `L-25c` | `E.phase4MissingHeaderNamesAndControlNegative:3200–3249` | `for in arr:`、`const A`、`func ():`、`var = 1` 的 code/facet、精确缺位及健康子节点组合；缺迭代名现有载体是空字符串加诊断，不臆造 AST 名字节点。无名 enum 合法对照；不注册空名由 `R6` 验证。 |
| `L-27a` | 公开布局：`F.tokenizeSuppressesBracketIndentationErrorsWhileKeepingOuterIndentationAndCloserErrors:124–155`、`T.multilineModeSuppressesNewlinesAndIndentation:702–724`；托管 lambda：`T.managedExplicitMultilineModeOverridesParenStackForLambdaSuites:727–773`、`E.bidirectionalLayoutReplayHandlesIndentDedentCommentsCrlfBackslashAndLambdas:2385–2433` | 同一多行 lambda 通过公开分词、Parser 与纯 Java 补全三个实际入口验证：公开 token 抑制括号内布局，托管解析/补全识别真实套件与闭合后外层缩进。现有公开布局测试不是同一 lambda 的覆盖。 |
| `L-27b` | 换行与 EOF：`T.crlfNewlinesAreNormalized:778–790`、`T.escapedCrlfInsideStringAdvancesLineOnce:793–800`、`T.strayCarriageReturnReportsErrorAndScanningContinues:803–822`、`T.eofSynthesizedNewlineAnchorsToPhysicalEofWithoutTrailingNewline:827–833`、`T.trailingNewlineAdvancesLineOnceAtEof:836–842`、`T.pendingDedentsFlushBeforeEofAndAnchorToPhysicalEof:845–869`、`T.multilineStringAtEofAnchorsBytesToPhysicalEof:942–953`。字节和列：`T.codePointColumnsDistinguishCjkEmojiAndTabsFromUtf8Bytes:979–994`、`T.longSingleLineKeepsExactPhysicalEndColumnAndEof:997–1006`、`T.lexicalErrorsProduceValidPhysicalCoordinatesAndByteRanges:1009–1039`、`T.physicalCoordinatesAlwaysMatchTokenByteRangesAcrossValidAndErrorInputs:1042–1092`、`E.errorAfterEmojiLiteralStaysInBounds:933–936` | 用精确范围替换 emoji 错误测试对宽泛 `recoverableParse` 边界检查的依赖；组合 CRLF、emoji、CJK、tab、末尾数值、三引号、孤立 CR、错误 token、EOF 合成布局、超长行。独立计算 token 一基 code point 列和 AST 零基 UTF-8 列。 |
| `L-28.1` | `C.trustedTypeContinuationsAreLocalAndIndependentOfAst:1145–1148`、`C.trustedTypeContinuationDoesNotMakeTheUnclosedTypeStrictlyValid:1151–1161`、`C.trustedTypesDistinguishOpenCaretSlotsAndCommaOwners:1197–1200` | 保留可信更深缩进续写的两入口 `TYPE_POSITION`；补有真实 `]` 的硬换行例，如 `var x: Array[\n\tInner]`，验证 strict 仍拒绝，不只测缺 closer。对照合法签名内多行、显式续行、同级新句/硬声明和祖先逗号。 |
| `L-28.2` | `C.cursorGapsRequireAnUnfilledOwnedSlot:1233–1236`、`C.gapSlotsRespectGuardEntryAndSignatureOwnership:1320–1323`、`C.pureJavaCompletionKindsRespectTheRecordContract:1357–1368`、`C.typeAndCallTokenFallbacks:203–205`（虽名为 fallback，实际只走 facade） | 八类空槽：字典值、两种推断初始化、形参默认值为 `IDENTIFIER`；函数/信号首形参为 `PARAMETER_NAME`；函数头冒号后 `SUITE_START`；中缀 `not` 后 `EXPECTED_KEYWORD`。各锁 UTF-8 零宽及 null receiver；对照前缀 `not`、`is not`、非法签名、逗号后形参。普通 `x = target(\|)` 与单行默认值 `func f(x = target(\|)): pass` 通过 facade 和空 AST 两入口各跑 LF/CRLF+emoji，期望 `CALL_ARGUMENT`，不误判为签名槽。 |
| `L-28.3` | `C.healthyCompletedTypesDoNotAbsorbFollowingExpressions:382–386`、`C.recoveryTypeSlotsPreserveDiagnosticsAndSurvivingNodes:409–432` | 保持健康样本逐例去光标零诊断，不恢复按输入排序截断；恢复样本独立检查诊断序列、范围、分类和存活节点。新 `extends` 变体放进健康流，另设 AST 与 token 行为确实不同的恢复样本。 |
| `L-30b` | `P.spacedInferredTypeMarkersPreserveSourceTextAndRange:2356–2370`、`Ref.inferredTypeMarkerKeepsSourceSliceIncludingInnerWhitespace:201–211`（参考夹具仅有 `:=`、`: =`） | 用 `:=`、`: =`、`:\t=` 的变量、常量与形参对照锁零诊断、源码 slice、字节范围和健康 parity；不将词法 token 合并。 |
| `L-30d` | `P.staticInitIsAPlainFunctionDeclarationAndEnforcesSignatureRules:710–743`、`P.constructorReturnValueIsRejected:2536–2548` | 分拆缺 `static`、普通参数、rest 参数、值 `return` 的稳定 code、范围、owner/facet 正负例；合法静态初始化仍是 `FunctionDeclaration`，裸 `return` 与同名 lambda 仍健康，显式返回类型语义检查保留给 analyzer。 |
| 原 3 | `E.unclosedContainersStopAtOuterDeclarationsAndStatementBoundaries:2287–2349`、`E.nestedUnclosedPrintAndCloserReplacementFollowOwnershipRules:2352–2382`、`E.arrayPatternMissingCommaRecognizesWildcardStarterAndSynchronizeReclaimsIndentedContinuation:3450–3481` | 矩阵覆盖 `[`, `{`, `(`、尾逗号，后继 `func`、同级 `enum`、`print(2)` 和形似 `if cond:` 的真实语句；检查匹配 closer 的对照与合法 lambda 体不被提前截断。 |
| 原 5c | `C.recoveredDelimiterBoundariesWorkWithAndWithoutAst:694–697`、`C.onlyExactClosersEnterThePartnerTable:718–746`、`C.completionScansTheBracketOracleAtMostOncePerStream:1087–1101` | 给 `[1)\n\ta.\n\tb\|` 和 `[a.)\n\tb\|` 的双入口 LF 基线增加 CRLF+emoji 版本：`b` 仍为 `IDENTIFIER`，替换范围精确；`(a.\n\tb\|)` 合法为 `MEMBER_ACCESS`。恢复替换不是精确配对；保留单遍 Oracle 门禁。 |

`C.assertCase(Case, boolean):1419–1446` 是断言助手（其中 `:1431–1438` 校验零宽），不是独立行为测试；测试通过它分别进入 facade 或空 AST 分析。

线性覆盖单列：`E.phase4LinearScanBoundsAndParserReuseBalanceInvariant:3270–3303`、`E.unclosedParameterListYieldsReturnArrowAndSingleLineColonWithLinearSignatureScanning:3586–3616` 已有规模对照和内部计数；`C.completionScansTheBracketOracleAtMostOncePerStream:1087–1101`、`C.allCompletionPrefixesKeepUtf8RangesAndReceiverInvariants:1105–1125` 检查单扫描及截断输入。`T.unmatchedCloserStormScalesLinearlyInStandaloneAndManagedModes:1169–1188` 的计数是 token 数，不能单靠它证明工作量线性。新增 `R2` 错配混合风暴、长行与大量短行、补全硬边界与多层诊断回退的递增规模门禁，见 §4.1。

### 2.7 `RG-7`：设计边界与独立 GDCC 联调

**无 AST 回退。** `GdScriptParserFacade.java:52–55` 的公开补全先 parse，再用 AST 与 token 推断。在测试中，辅助方法 `C.assertCase(Case, boolean):1419–1446` 的 token 分支通过辅助方法 `C.emptyAst:1468–1470` 构造空 AST，不经过 facade。`CompletionContextAnalyzer.java:175–191,376–392` 在空 AST 时走 token receiver 回扫。`C.astCastReceiverCanStartAtTheExactRecoveryBoundary:1057–1060` 对照这两个入口：`x as Array[int]` 的 AST receiver 可以覆盖整个 cast，无 AST 的 token 回退只得到 `[int]`。保留独立回退的分类、范围、硬边界和有据可依的保守 receiver；不在该路径重建 Pratt 优先级树或生产候选。旧 `infra.treesitter` 的五种分类和入口保持原样。

**GDCC 单独授权、单独验收。** `/home/superice/projects/gdcc` 基线仍在 `build.gradle.kts:63` 依赖 GDParser `0.6.0`；`GdScriptParserService` 生产路径使用 `parseCstRoot`，并将诊断扁平化为 `parse.lowering`；`FrontendSnapshotCompletionService` 仍使用 `infra.treesitter`。下列是升至 `0.7.x` 的联调项，不是本仓库已交付实现：

| 项目 | 下游验收 |
|---|---|
| 通用诊断 | 切纯 Java 后保留 code、范围、诊断级别及同代 AST/owner/facet。身份不跨快照持久化；按原因去重，不吞独立语义诊断。编辑期可遍历健康部分与可编译性分别判定。 |
| 受损表达式与 `super` | sema 和 CFG 不对 `RecoveredInfixExpression` 求值或作正常降级。`super.ba` 没有 marker，仍须以 `SUPER_MISSING_CALL`（`parse.super.missing_call`，`Parser:9440–9446`）拒绝编译；父类确有匹配方法时，合法 `super.ba()` 对照通过。 |
| `L-01` | 验证正负号、基数、`Long.MIN_VALUE` 的常量求值、导出注解和负值模式；不恢复带符号数字 lexeme。 |
| `L-08` | 受损 `AssignmentExpression` 留在值槽用于编辑，不进入值位置求值或 CFG 降级；诊断可抑制次生错误而非整棵树。 |
| `L-11` | 字典模式先检查键存在；只有条目有值模式时才读取对应值、递归匹配；保留开放/封闭长度门禁。覆盖常量二元键折叠、缺键/额外键/混合项；普通字面量要求值非空且保持 `2*N` 布局。 |
| `L-12` | 注解收集与分析继续按源序处理，保留作用域、目标验证和独立指令语义；独立指令不抢待绑定目标，属性恢复注解不作健康修饰，不与解析器重复报错。 |
| `L-13` | 类骨架和语义保留继承首写优先；不得把第二个 `extends` 当父类，也不因首目标损伤而用重复目标替换。 |
| `L-25c` | 缺名声明、形参或循环迭代变量不注册虚假符号，健康套件仍可分析及补全，不进入 `INTERNAL_FAILED`。 |
| `L-28` | 接入纯 Java `CompletionContext`，覆盖八枚举与新旧客户端映射。`PARAMETER_NAME` 默认不推荐旧变量，`SUITE_START` 接语句关键字，`EXPECTED_KEYWORD` 在本槽只建议 `in`；旧公开映射必要时降为 `UNKNOWN`。验证同代 receiver、CRLF/UTF-8 替换范围和健康体。 |
| `L-30a/b/c` | 根据 parser code 合并派生左值/直接调用错误，同时保留真实语义限制；推断标记接受 `:=`、`: =`、`:\t=`；过渡期拒绝旧声明式及新表达式式的裸 lambda，不误拒绝合法 `.call()`、赋值或实参。 |

## 3. 契约与架构决策记录

以下各项均已完成用户裁定或明确边界，作为 `R1`–`R6` 的实施依据；未获单独授权前仍不越界修改公开 AST/API 或下游 GDCC 仓库。

| 决策项 | 状态 | 最终裁定与实施边界 |
|---|---|---|
| 决策 1（`1B`）：`ParseOutcome` 五态扩展与 D6 容器吸收（`L-07/08`） | **已确认（`1B`）** | 在包私有 `ParseOutcome<T>` 中新增 `EnclosedDamage<T>(T value)`，表达“本层语法结构完整且边界已收口，但子树含损伤”（并删除无调用的 `isOk()`/`valueOrNull()`）。闭合的数组、字典、调用和下标吸收子损伤而不伪装 `Ok`，让 `foo([1+]).bar` 继续折叠，并让 Lua 键校验对 `{[1+] = 2}`、`{foo(1+) = 2}` 一致报 `DICTIONARY_INVALID_LUA_KEY`。普通分组不建外层节点：真实 `)` 闭合时继承内层结果，`(1+)` 仍为 `Damaged`，因此不二次报错；`([1+])` 继承 `EnclosedDamage`。同步设计与架构文档的五态说明。 |
| 决策 2（`2B`）：`parseTypeInternal` 统一返回 `ParseOutcome<ParsedType>`（`L-09/20`） | **已确认（`2B`）** | 将 `parseTypeInternal` 改造为返回 `ParseOutcome<ParsedType>`（保留 `ParsedType.isContainer()`，公开 `TypeRef` 与 serde 不变），覆盖全部 6 处 `parseType` 调用点 + 1 处递归调用，彻底消除 7 处 `diagnosticCount() == diagBefore` 计数判断：`Missing` 由调用方补发上下文 `MISSING_TYPE_*`；`Failed`（如 `Array[]`/非法 `void`）禁双报；`Damaged`/`EnclosedDamage`（如 `Foo.` 含点号范围、`Array[int` 缺 `]`、`Array[Array[int]]` 非法嵌套）让 `parseCast`/`parseTypeTest` 准确传播损伤、让 `parseFor` 触发同行 `in` 同步；同时核实并加固 `as`/`is` 无活跃帧时 `MISSING_CLOSER_TYPE_BRACKET` 的安全发射。 |
| 决策 3（`3A`）：未配对 `Dictionary` 的坏逗号优先级（`L-23`／`R2.2`） | **已确认（`3A`）** | 保持 `shouldContinueDictionaryTypeComma`（`Parser:7181–7192`）“有祖先逗号所有者且 `Dictionary[` 未精确配对时让逗号给外层”的规则不变，不引入脆弱的坏逗号启发式；保护同行真实第二实参 `g(func() -> Dictionary[String, Array[i])` / `other` 与 `C:1214–1222` 的外层 `IDENTIFIER` 解释，同时在 `R2.2` 补齐跨行坏嵌套的非法嵌套/缺闭合 strict 拒绝与 `node.pa` 存活/补全回归测试。 |
| 决策 4A（`4A`）：语句尾孤立 closer 诊断码（`L-10`／`R3.1`） | **已确认（`4A`）** | 保留 `UNEXPECTED_CLOSER`（`parse.unexpected_closer`，`Codes:267–282,449–457` 已注册所需全部配对），按位置精确绑定归属：同行尾 `pass )`/`return 1 )` 绑原语句 `statement.trailing`，跨行/套件首 `)` 绑存活套件宿主 `suite.body`，类体首 `)` 绑存活类 `class.body`，真正无宿主的顶层 `)` 保持 `null`；祖先拥有的 closer 不进入上述孤立恢复路径。 |
| 决策 4B（`4B-A`）：套件熔断归属（`L-29`／`R3.2`） | **已确认（`4B-A`）** | 在 `DiagnosticCodes.java:458` 为 `STATEMENT_TOO_MANY_ERRORS`（`parse.statement.too_many_errors`）开放 `Set.of("suite.body")`。当熔断发生在存活函数/构造器/lambda/控制块等套件宿主内时归属该宿主，无宿主时保持 `null`，不授予 `SourceFile` 或兜底所有者，使 `R3` 起的位置感知 `I5` 门禁实现零例外。 |
| strict WARNING 夹具（`L-11`） | 保持现状 | 当前纯 Java 无调用 `Tracker:391–395` 的生产 WARNING 路径；保留合成结果的 `F.requireStrictSkipsWarningsAndThrowsFirstError:67–78` 和 `F.requireStrictDoesNotThrowOnWarningsOnly:81–88`，不为测试发明解析规则。 |
| 配对、跨行与诊断主次（`L-05/16/24`） | 已定基线 | `L-05` 混风格重复项保留首项、排空后项且每坏项发单一主诊断；`L-16` 以键末消费 token 物理行判同行值且保持 `key.range()` 解包合同；`L-24` 只交接受损签名后的可信 `->` 且负对照 `func f(a):\n\t-> int: pass\n` 不误判为返回类型。 |
| 旧路径与跨仓（`RG-7`） | 保持边界 | 不将纯 Java 八枚举塞进旧五枚举，也不为独立无 AST 补全回退重写 Pratt `as` 解析器；GDCC 联调（`R6`）待独立授权后跨仓执行。 |

## 4. 分阶段修复措施

### 4.1 公共验收门禁

1. **先复现，再修复。** 每个缺口在基线加定向行为断言，记录 AST 分支/兄弟、稳定 code、精确字节范围、可达 owner/facet 与预期诊断数。实现后用相同输入复跑；健康对照零诊断。已修复项只增强覆盖和更正命名，不改预期来接受新退化。
2. **定向验证。** Linux 用 `script/run-gradle-targeted-tests.sh --tests <类名或逗号分隔类名>`，类名按各阶段列出。每阶段定向 0 失败；在发布/开 PR 前，完成 `./gradlew clean build --no-daemon --info --console=plain`，记录各类及全量计数、0 失败和 0 错误；跳过项须说明。基线 25 类/1886 项不是新增测试后的固定数量。
3. **`I1`–`I4` 和线性门禁。** 覆盖 LF、CRLF、emoji、逐截断/EOF、错闭合、深度/套件预算和复用同一 parser 后的健康解析；检查三栈回基线、无挂起布局。对最坏样本按至少四个翻倍规模（如 256、512、1024、2048 单元）记录实际输入 token/字节数及扫描、配平或回退步数。每档满足从单次处理与摊销规则推得、跨规模固定的 `C*N + K` 上界；`C`、`K` 与计数位置随测试说明，不按结果放宽。Oracle 每个输入流至多调用一次；耗时只作补证。
4. **`I5` 分阶段验收。** `R1`、`R2` 零新增违规；所有新增或改变的诊断均须有稳定码及符合位置和宿主身份的归属。只暂留 `R3.2` 位置门禁清单中已记录且本阶段未改变的基线违规：同行尾、跨 `NEWLINE`、套件或类体首项前的孤立 closer；类体 `static`、`% ^ &`、`func 1`、`"doc" var a = 1`；重复参数被丢弃的 lambda 默认值；套件熔断。不得扩大豁免或仅按 code 放行。从 `R3` 起这些违规全部归零，执行完整的位置感知 `I5`。合法的独立词法错误和真正无宿主的顶层孤立垃圾不属于违规。
5. **退出条件。** 每阶段 `git diff --check HEAD` 清洁，专测和范围门禁通过；双独立审阅意见已处理并复核，无未处置阻塞、高或中风险，契约事项有明确决定。无法解决的问题保留为显式阻塞，不签为已完成。文档整编本身不触发实现构建。

### 4.2 `R1`：损伤状态保真（`RG-1`，按决策 `1B + 2B`）

**目标与前置：** 扩展 `ParseOutcome<T>` 五态（新增 `EnclosedDamage`）并将类型解析统一纳入 `ParseOutcome<ParsedType>`，消除伪装 `Ok` 与 7 处 `diagBefore` 诊断计数判断，同时保留 D6 吸收续折与公开 AST 合同。不依赖 `R2` 即可独立实施。

| 子任务 | 改动锚点与约束 | 行为必测 |
|---|---|---|
| `R1.1` `ParseOutcome` 五态扩展与容器/Lua 键对齐（`1B`） | `ParseOutcome.java:16–69`、`Parser:8882–9976`（重点 `:9036–9085,9097–9276,9657–9685`）。新增 `EnclosedDamage<T>(T value)`，清理死方法 `isOk()`/`valueOrNull()`。明确五态合并：本层完整但子项为 `Damaged`/`EnclosedDamage`/`Missing`/`Failed` 吸收时返回 `EnclosedDamage`；本层残缺返回 `Damaged`。`parsePrecedence`/`parseSuffixChain`/`absorbSlot`/`parseStatement` 对 `Ok`/`EnclosedDamage`/`Damaged` 维持一致续折与槽位吸收；`Parser:9188–9202` Lua 键校验对 `Ok` 与 `EnclosedDamage` 均执行键形态检查（`{[1+] = 2}`、`{foo(1+) = 2}` 报 `DICTIONARY_INVALID_LUA_KEY`），仅 `Damaged`（如 `{(1+) = 2}`）豁免。同步更新 `doc/pure-java-recovery-and-completion-plan.md` 与 `doc/godot-java-parser-implementation.md` 的 4 处旧 `ParseOutcome` 描述。 | `L-07/08`（`{:}`、`{"a":1,:, "b":2}`、`g([a=1], 2)`、`{1: a=1}`）断言结构、零宽 marker、精确诊断/归属与 strict 拒绝；Lua 键三对照 `{[1+] = 2}`、`{foo(1+) = 2}`（均报 `DICTIONARY_INVALID_LUA_KEY`）与 `{(1+) = 2}`（不二次报）；`foo([1+]).bar` 吸收续折正对照；健康同级实参、尾逗号、空容器和合法赋值。 |
| `R1.2` 类型解析五态化与 `ParseOutcome<ParsedType>`（`2B`） | `Parser:7157–7179,7194–7274,7372,7877,8548,9579–9649`。`parseTypeInternal` 改为返回 `ParseOutcome<ParsedType>`，6 处 `parseType` 调用点 + 1 处递归统一按 `Ok`/`EnclosedDamage`/`Damaged`/`Missing`/`Failed` 分派，删除 7 处 `diagnosticCount() == diagBefore` hack；修复 `Foo.` 使 `typeEnd` 包含已消费 `.` 并返回 `Damaged`；`Array[int` 缺 `]` 返回 `Damaged`，`Array[Array[int]]` 非法嵌套返回 `EnclosedDamage`/`Damaged`；核实并加固 `as`/`is` 无活跃帧时 `MISSING_CLOSER_TYPE_BRACKET` 的归属发射，禁止抛出 `IllegalStateException`。公开 `TypeRef` 与 serde 保持不变。 | 三个 `L-09` 例（含 `Foo.` 含点号字节范围与 `var`/`param`/`return` 处点号范围）、`x as`/`x is not`、`Array[]`/`void`、健康 cast/type test、`as`/`is` 顶层独立表达式缺 `]` 防崩用例；补 marker/类型残片 serde 和 UTF-8 精确范围。 |
| `R1.3` `for` 保住迭代源 | `Parser:5652–5702`。改用 `parseTypeInternal(false)` 返回的 `ParseOutcome<ParsedType>`：`Missing` 补发 `MISSING_TYPE_FOR_ITERATOR`，凡非 `Ok`（`Missing`/`Failed`/`Damaged`/`EnclosedDamage`）均复用现有边界守卫执行同行 `in` 同步。 | 两个非空坏类型（`Foo. ??? in xs:`、`Array[int ??? in xs:`）保住 `xs`/`pass`/外层兄弟且无假 `FOR_MISSING_IN`；真正缺 `in` 的有/无冒号、健康类型、祖先 closer 或逗号前停住。 |

**出口：** `GdScriptParserTest`、`GdScriptParserErrorRecoveryTest`、`GdScriptParserRangeInvariantTest`、`GdScriptParserFacadeTest`、`AstSexprSerdeTest`；两个 `for` 样本均保 `xs`/`pass` 且无 `FOR_MISSING_IN`，`{foo(1+) = 2}` 与 `{[1+] = 2}` 行为一致且 `{(1+) = 2}` 无二次错，`foo([1+]).bar` 不回归；零新增 `I5` 违规，改变或新增的诊断全部检查准确归属，只暂留 §4.1 所列未触及的基线例。

### 4.3 `R2`：结构边界与熔断配平（`RG-2`，按决策 `3A`）

**目标与前置：** 局部错误不夺祖先 token。`R2.1`、`R2.3` 可独立复现；`R2.2` 按决策 `3A` 保持未配对 `Dictionary` 让逗号给外层的规则，在 `R1.2` 类型结果合同落地后补齐正反回归验证。

| 子任务 | 改动锚点与约束 | 行为必测 |
|---|---|---|
| `R2.1` 同步中的局部括号 | `Parser:3243–3346,1051–1059`。局部 opener 使用与祖先边界兼容的匹配/替换规则；不反复遍历无界祖先，不把匹配的外层 closer 当同步垃圾。 | `L-18`/原 5b 的 `= [1)`、局部精确匹配与单替换、祖先逗号、`assert(1+, func(): pass)`、健康 lambda。 |
| `R2.2` 泛型内外逗号回归锁定（`3A`） | `Parser:7181–7192,7223–7259`（不改动未配对 `Dictionary` 让逗号给外层的规则，不绕过嵌套类型禁令或全局放开 `TYPE_BRACKET` 多行）。 | 跨行坏嵌套 `g(func() -> Dictionary[String, Array[\n,\n\tnode.\n\tpa\n)` 锁非法嵌套/缺闭合 strict 拒绝、无体 lambda 与 `node.pa` 存活及双入口 `MEMBER_ACCESS`；同行正对照 `g(func() -> Dictionary[String, Array[i])` 与 `other` 锁真实第二实参、双入口外层 `IDENTIFIER` 及 strict 拒绝；已配对 `Dictionary[String, int]` 测零诊断。 |
| `R2.3` 未入栈错闭合 | `Parser:2701–2853,3352–3375`。未入栈链按种类索引或等价有界配平；优先匹配真祖先，否则单层替换，不能清整条链或抬高预算。 | 两个 `L-26` 样本、全配对版本、EOF 截断、260 层内外兄弟，以及递增错配风暴的实际计数。 |

**出口：** `GdScriptParserErrorRecoveryTest`、`GdScriptParserRangeInvariantTest`、`GdCompletionContextTest`；原 5b 的 `pass`、`after` 皆存活，`R2.3` 不新增假缺冒号，遵守三栈、单扫描及线性门禁。零新增 `I5` 违规，改变或新增的诊断全部检查准确归属；只暂留 §4.1 所列、未触及的基线例。

### 4.4 `R3`：诊断归属与块边界（`RG-3`、`RG-4`，按决策 `4A + 4B-A`）

**目标与前置：** 错误绑定到存活构造、不连健康外层；限制同一坏项的重复诊断。按决策 `4A`（保留 `UNEXPECTED_CLOSER` 按位置绑定）与 `4B-A`（`STATEMENT_TOO_MANY_ERRORS` 开放 `suite.body`）落地；与 `R2` 的祖先 closer 行为联验，与 `R1` 共测类型/签名组合。

| 子任务 | 改动锚点与约束 | 行为必测 |
|---|---|---|
| `R3.1` 同行语句尾 closer（`4A`） | `Parser:3524–3675,1160–1167`，已注册配对见 `Codes:267–282,449–457`。保留 `UNEXPECTED_CLOSER` 诊断码与 `NEWLINE` 边界信息，先判祖先归属再区分同行尾部与下一行；不能把收尾读取的每个 closer 都绑给语句。 | 套件内同一行 `pass )`、`return 1 )` 均发 `UNEXPECTED_CLOSER` 并绑原语句 `statement.trailing`；`func f():\n\tpass\n\t)\n\tpass\n` 只能绑套件 `suite.body`。普通尾垃圾不回归，祖先 closer 不被当作垃圾；断言范围、稳定码、owner/facet 和后继。 |
| `R3.2` 独立 closer、丢弃树与熔断归属（`4A + 4B-A`） | `Parser:2955–2962,3017–3040,3913–3918,3999–4113,4463–4490,5448–5450,8334–8337,8489–8496,8668–8671`，`Tracker:614–631`、`Codes:267–282,449–458`、`V:1132–1141`。在 `Codes:458` 将 `STATEMENT_TOO_MANY_ERRORS` 改为 `Set.of("suite.body")`，在 `parseSuiteImpl` 有存活套件宿主时归属该宿主（无宿主保持 `null`）；沿合法授权回退，位置检查不代替注册闭集。 | 首项前 `func f():\n\t)\n\tpass\n` 绑 `suite.body`，`class C:\n\t)\n\tpass\n` 绑 `class.body`；跨 `NEWLINE` 的套件 closer 仍绑 `suite.body`，与 `R3.1` 同行尾一起纳入位置门禁。祖先 closer 不走这三类恢复，真正无宿主的顶层 `)\n` 保持 null。`class C:\n\tfunc 1\n\tpass\n`、`class C:\n\t"doc" var a = 1\n\tpass\n` 均断言 `parse.syntax.unexpected_token` 的 owner 与存活 `C` 是同一对象、facet 为 `class.body`、后续 `pass` 保留。修默认值旧锁、类体 `static`/`% ^ &` 和熔断（存活函数/lambda 熔断归属宿主 `suite.body`）；独立词法正例仍通过。 |
| `R3.3` 属性重复主诊断 | `Parser:7572–7619,7784–7787`。保持第一项、排空后项，对每坏项用一个主诊断，不降低对独立错误的报告。 | 两种 getter 混写、setter 对照、单纯风格错、全合法，锁各诊断位置及健康后续成员。 |
| `R3.4` 字典键结束同行 | `Parser:9134–9135,9216–9238,9250–9255`。用键末消费 token 的物理行决定值候选，不扩张解包后 `key.range()`。 | `var d = {(\n\ta) 2}\n` 只报一次分隔符错；`key.range()` 是 `[12,13)`，`DictEntry.range()` 从字节 9 的 `(` 开始，覆盖 `()` 和 `2`。另测 `{1 2}`、真正跨行下一键、健康括号键和 EOF。 |
| `R3.5` 签名续词与 suite | 同时改 `shouldYieldColonToEnclosingSuite`（`Parser:7901–7944`）与参数表到返回类型的交接（`:8538–8543`）；处理缺 `)` 退出多行带来的 `NEWLINE`/`INDENT` 回放（`:2916–2921,1425–1458`）。只交接受损签名后的可信 `->`；不能通用跳过布局。 | `L-24` 保留 `int`、真实套件冒号与起点、体内 `pass`、顶层 `after`；负例 `func f(a):\n\t-> int: pass\n` 不得把套件解释为返回类型。保留合法 `pass` 体、合法类型续行及无体声明。 |

**出口：** `GdScriptParserTest`、`GdScriptParserErrorRecoveryTest`、`GdScriptParserRangeInvariantTest`、`DiagnosticRecoveryFoundationTest`；§4.1 暂留的 `I5` 违规和旧缺陷锁均归零，完整位置门禁能区分同 code 的语句尾、容器和顶层上下文；属性坏项与分组字典例各一个主诊断。

### 4.5 `R4`：语法守门与语言对齐（`RG-5`）

**目标与前置：** 保持纯 Java 对非法语法的承诺、可遍历性和后续结构，使用 `R3` 确立的归属门禁。以本地 Godot 引用作语言对照，旧路径只作有意分歧对照。

| 子任务 | 改动锚点与约束 | 行为必测 |
|---|---|---|
| `R4.1` 注解跨独立指令 | `Parser:4019–4026,5229,7081–7145`；保留 `:3700–3715` 的脚本前缀行为。同作用域内的独立指令不打断待绑定注解；保持扁平注解 AST，不新增 target 字段。 | §2.5 的嵌套类体、`var prefix = 1` 后根成员、函数套件三种失败夹具与干净脚本前缀对照。查源序、目标校验与 code/owner/facet；错误级别、真实悬空和访问器隔离仍正常。 |
| `R4.2` 嵌套类体首重复 `extends` | `Parser:4195–4199,4240–4258,4061`。消费重复目标但不公布第二个父类；已占槽不依赖首目标能否完整解析。 | 体首连续两条、表头加体首、坏首目标、外层/内层独立、后续合法成员，首写优先和一条专用重复诊断。 |
| `R4.3` 损伤左值去重 | `Parser:9657–9704,9808–9824,9916–9922`。只抑制由同一损伤导致的派生错，不能改变表面可写/可直接调用规则。 | `(a ?) = 2`、`(a ?)(2)` 单原发错误；合法和非法左值/直接调用对照、恢复 AST 仍可遍历。 |
| `R4.4` 分组裸 lambda | `Parser:5393–5431,8990–9034`。按最终表达式形态守门，不按是否从 `func` token 开始判定；诊断绑定正确语句，保留 `lambdaEnded` 行为。 | 匿名、具名、多行分组裸 lambda；直接裸 lambda；合法 `.call()`、合法 lambda 实参/值、类体缺名函数。后续 `pass` 均保留。 |

**出口：** `GdScriptParserTest`、`GdScriptParserErrorRecoveryTest`、`GdScriptParserRangeInvariantTest`、`GdParserParityTest`；新增例逐项验证稳定码/范围/身份；健康 parity 不降低阈值，各构建一致拒绝裸 lambda。满足 §4.1。

### 4.6 `R5`：行为测试、坐标与线性门禁（`RG-6`）

**目标与前置：** 覆盖 §2.6 的全部 22 个已修复子项及原 3/5a/5c（`L-07/08` 已按决策 `1B` 留在 `R1.1`）。可先独立补现状断言，`L-04` closer 和全局归属/边界的组合在 `R1`–`R4` 定案后集成；未确认的失败先定位，不扩大为新实现任务。

| 子任务 | 锚点与改动边界 | 行为必测 |
|---|---|---|
| `R5.1` 词法与解析、坐标 | 从 §2.6 对应行列出的 `T`、`F`、`P`、`E` 测试扩展真实公开/托管入口；坐标用独立字节和 code point 计算。 | 完成 `L-01/02/03/27a/27b` 与原 3/5a 矩阵；LF/CRLF+emoji、EOF、错闭合与健康配对。 |
| `R5.2` AST、严格入口与诊断 | 从 §2.6 对应行的各行为测试及 §3 合成过滤测试扩展；清理反义命名，serde/walker 测真实返回树。 | 完成 `L-04/06/11/14/15/17/19/21/22/25a/25b/25c/30b/30d` 行为矩阵。查精确 marker、诊断和兄弟；用 `I5` 位置门禁验身份。 |
| `R5.3` 补全入口和独立回退 | 扩展 §2.6 的 `C` 行为测试和 §2.7 的双入口对照；生产锚点是 `GdScriptParserFacade.java:52–55` 与 `CompletionContextAnalyzer.java:175–191`。不改变旧五分类或重建 Pratt。 | 完成原 5c、`L-28.1/.2/.3` 矩阵；硬换行已有 `]` strict 拒绝、普通和默认值内 `target(\|)` 的两个入口、CRLF+emoji 错闭合分类及健康/恢复数据流。 |
| `R5.4` 复杂度与边界 | 扩展 §2.6 线性测试及 `V:1132–1141` 的归属断言。沿用内部可观察计数，不用墙钟或 token 数代替步数，避免改变生产行为以配合测试。 | 按 §4.1 做四档递增、三栈收口与重入，覆盖连续错配、深层 `match`、长行、短行、逐截断及诊断回退。 |

**出口：** `GdTokenizerTest`、`GdScriptParserFacadeTest`、`GdScriptParserTest`、`GdScriptParserErrorRecoveryTest`、`GdScriptParserRangeInvariantTest`、`GdParserParityTest`、`GdParityReferenceTest`、`GdCompletionContextTest`、`CstToAstMapperTest`、`AstSexprSerdeTest`、`NodeChildrenTest`、`ASTWalkerTest`、`SourceTextIndexTest` 与旧 `infra.treesitter.CompletionContextTest`。§2.6 所有项有可定位的行为断言；执行 §4.1 定向、复杂度、审阅、diff 和发布前 `clean build`。

### 4.7 `R6`：GDCC 独立联调（`RG-7`）

**目标与前置：** 单独获授权并确定 GDParser `0.7.x` 可消费版本。先验证 parse/serde 与快照诊断传输，再验证 sema、CFG 与 LSP；不得在本仓库阶段越权修改下游。

| 子任务 | 接点与约束 | 联调必测 |
|---|---|---|
| `R6.1` API、诊断与语义门禁 | GDCC 的 `GdScriptParserService`、同代快照/诊断表、类骨架、注解收集、sema 和 CFG；先迁入口，后接恢复链接。 | §2.7 通用诊断及 `L-08/25c/30a/30c`；`RecoveredInfixExpression` 不求值，不因无 marker 而放过带 `SUPER_MISSING_CALL` 的 `super.ba`；`L-13` 首写继承优先，`L-12` 源序及独立指令语义进入分析。健康编辑可用与编译拒绝分开。 |
| `R6.2` 数值、模式、推断 | `FrontendMatchSupport`、数值/常量解码、类型标记读取和 CFG。 | `L-01/11/30b`：符号和最小整数、key-only 长度/缺键/有键而不取值、常量二元键、普通字面量，以及空格/tab 推断。 |
| `R6.3` 补全客户端 | `FrontendSnapshotCompletionService`、枚举映射、候选路由与旧客户端降级。 | `L-28` 的八种分类、健康/恢复快照、UTF-8/CRLF 范围和旧 `UNKNOWN` 路径。 |

**出口：** 下游定向和端到端用例均通过并记录命令及计数，报告版本、仍未支持的能力及任何编译/编辑边界差异；两仓分别执行适用的 diff、全量与审阅门禁，不把下游退出条件视为本仓库代码改动。

## 5. 后续补强实施进度

下表只记录新版措施；旧阶段 1–6 的完成记录不迁入本表。

| 阶段 | 子任务 | 状态 |
|---|---|---|
| `R1` 损伤状态保真 | `R1.1`–`R1.3` | 已完成（双审 APPROVE，505 定向通过） |
| `R2` 结构边界与熔断配平 | `R2.1`–`R2.3` | 已完成（双审 APPROVE，1628 定向通过） |
| `R3` 诊断归属与块边界 | `R3.1`–`R3.5` | 待实施 |
| `R4` 语法守门与语言对齐 | `R4.1`–`R4.4` | 待实施 |
| `R5` 行为、坐标与线性门禁 | `R5.1`–`R5.4` | 待实施 |
| `R6` GDCC 独立联调 | `R6.1`–`R6.3` | 待实施（跨仓，需独立授权） |

`R1` 实施摘要：表达式与类型按五态传播（新增 `EnclosedDamage`），透明分组保留内层状态，Pratt/特化前缀/lambda 头部词法与结构损伤完整保真；`as`/`is` 保留坏类型前缀且为独立类型诊断兜底建立 `type.argument` 归属；`for` 在非 `Ok` 类型后于同行同步 `in`（含显式 `\` 续行）。公开 AST 与 serde 不变；同步更新三份设计文档。定向 5 类 505 项全过（0 失败/错误/跳过），`review-expert-b` 与 `review-expert-c` 双 `APPROVE`。

`R2` 实施摘要：同步丢弃局部垃圾时优先按同类 opener 配对；真实祖先 closer 与未被当前同步区内配对括号保护的外层逗号交还宿主，括号预判按 tokenizer 的数字和字符串边界跳过字面量，并在单次扫描中以 O(1) 栈传播记录非 closer 行首最小缩进。深度熔断按种类计数，优先保护真实祖先与纯闭合行，行首边界早于未入栈 opener 截断，不匹配时只替换最内一层；精确匹配的内层可接回后缀和实参，外层未配对帧仍按熔断路径收口，避免诊断级联。嵌套 lambda 签名/体与 nodepath/成员取模边界对齐，仅当前恢复窗口内可达的已配对局部括号护盾 `match` 内层冒号，未配对或越界 closer 交还分支冒号。预算仍为 256，泛型逗号规则（`3A`）不变，补齐恢复 AST、strict 和双入口补全对照。两组四档线性验证均通过，每输入单次括号预判；定向 5 类 1628 项全过（0 失败/错误/跳过），`review-expert-b` 与 `review-expert-c` 双 `APPROVE`。
