# 纯 Java Parser 错误/缺失节点产出与补全能力迁移实施计划

日期：2026-10-08。状态：**恢复侧（B0-B9）已实施**（2026-10-15：sealed-outcome 协议、2C 闭合符诊断 + RecoveryLink、3A C3 槽位 marker、参数顺序保留全部落地；实现经 review-expert-a / review-expert-c 双评审修复后均 APPROVE）；**补全迁移（B10，§4.4）与旧路径弃用（§8.3）未实施**。计划主体经 review-expert-a / review-expert-c 多轮评审确认；§8.2 于 2026-10-14 按用户决策重写为 sealed-outcome 设计并纳入 2C/3A 修复方案；`:` 缺类型问题按用户决策接受。

本文依据 `doc/analysis/gdparser_0_7_pure_java_parser_regressions_report.md`（下称「回归报告」）、
`doc/analysis/gdparser_0_7_lsp_migration_research_report.md`（下称「迁移报告」）与
`doc/analysis/gdparser-0.7-pure-java-recovery-research.md`（下称「调研」）编写，目标是修订纯 Java 路径
「不构造 `ErrorStatement`/`ErrorExpression`/`MissingAttributeStep` 节点」的现行产出政策
（`doc/architecture-overview.md:190`、`doc/godot-java-parser-implementation.md:102-103`），在受损槽位发出错误/缺失节点，
并把 `CompletionContext`/`CompletionContextAnalyzer` 能力迁移到纯 Java parser 路径，供 GDCC LSP 使用。

## 1. 目标与范围

**本仓库（gdparser）范围内**：

1. 表达式内部失败位点按 sealed-outcome 协议（§8.2）产出 marker/部分节点：存活槽位填 `ErrorExpression`
   （区分 `ERROR`/`MISSING` kind），缺名点号与 `super` 前缀产 `MissingAttributeStep`，修复字典占位节点的
   父子范围越界；C1/C2 语句（if/while/for/assert/const/match）带受损槽位存活；C3 槽位（guard/enum 值/elif
   条件/字典 pattern key）填 marker（3A，§8.5）。
2. 参数顺序错误不再丢弃参数，保留参数身份与位置（Phase 2 #5，与参数默认值 marker 同批）。
3. 闭合符缺失（分组/数组/字典/调用/下标/注解/preload/assert）产出带 code 与 `RecoveryLink` 的零宽诊断（2C，§8.4）。
4. 新增纯 Java 路径的补全上下文分类能力（`GdScriptParserFacade.parseCompletionContext`）。
5. 更新两份事实源文档的产出政策条款。

**明确不做（本轮）**：`:`（非 `:=`）后缺类型保持零诊断（D7，已接受为与 vendor 一致的接受面）；
`parseType`/`parseNameAndType` 签名与行为不变；match `var` 绑定缺名仍丢弃（无节点可承载名字）；
增量解析（迁移报告已登记的非目标）；GDCC 侧消费改动（仅定义合同点，见 §7）。

## 2. 决策记录

### 2.1 本计划直接采用的决策

| 决策 | 内容 | 依据 |
|---|---|---|
| D1 恢复载体 | 复用已有 `ErrorExpression`（`CstIssueKind.ERROR`/`MISSING`）与 `MissingAttributeStep`，**不新增 AST 节点类型、不改 serde schema**（`AstSexprSchema.java:109,122,138` 已注册）、不改 `AstMappingResult`；`AstDiagnostic` 增加可空 `code` 与 `recovery` 组件并保留 4 参构造器（D4/§8.1） | 用户任务；调研 §7.2-7.3 |
| ~~D2 接受面~~ | **已被 D7 取代** | — |
| D3 编辑器范围 | super 前缀保留与补全上下文分类纳入本次交付；signature help DTO 属 GDCC 侧后续能力，本计划只保证 `CALL_ARGUMENT` 分类与 callee 定位 | 用户任务；调研 §8.5-5 |
| D4 诊断结构 | **API 稳定性不作约束**（用户 2026-10-08）：`AstDiagnostic` 直接修改，不兼容由下游承担 | 用户决策；调研 §7.4-7.5 |
| D5 旧路径弃用 | 旧 tree-sitter 补全/解析入口在本计划交付后**标注弃用**（`@Deprecated` + 迁移指引注释），移除另排（§8.3） | 用户决策 2026-10-08 |
| D6 sealed-outcome | §8.2 的损伤传播编码采用 **`ParseOutcome<T>` 密封接口**（用户 2026-10-14），取代此前的受损节点身份集合方案：损伤随返回值沿调用栈流动，吸收 = 宿主解包后返回 `Ok`，无需旁路状态机；代价是表达式族签名从 `@Nullable Expression` 改为 `ParseOutcome<Expression>`（接受大范围机械重构） | 用户决策；§8.2 |
| D7 `:` 缺类型 | **接受**（用户 2026-10-14）：已消费 `:`（非 `:=`）后缺类型与 vendor Godot 一致（静默、按未标注 Variant 处理），不补诊断、不实施原 Phase 2 #4 与 T11；登记为接受面事实而非偏差 | 用户决策；回归报告 §2 |
| D8 闭合符诊断 | 采纳 2C（§8.4）：缺闭合符产零宽诊断 + code + `RecoveryLink`（owner/facet），不改 AST 形状，不做纯几何归属 | review-expert-c 方案；用户决策 2026-10-14 |
| D9 C3 槽位 marker | 采纳 3A（§8.5）：guard/enum 值/elif 条件/字典 pattern key 的「置 null/丢槽」改为 Phase 1 式槽位 marker；字典 pattern 无载体分支整体 ERROR 回退 | review-expert-c 方案；用户决策 2026-10-14 |
| D10 C2 存活 | assert/const/match 值在槽位失败后**带 marker 存活并消费成功路径尾部**（`)`/`endStatement` 照走），取代「提前 return + 由 synchronize 吃残余」的过渡形态；vendor 对 assert/const 是丢弃，此处为有意优于 vendor 的恢复（登记偏差） | sealed-outcome 使存活消费等价可论证；§8.2.5 |

### 2.2 四态槽位合同（MISSING/ERROR 细化规则）

| 源码情形 | 槽位值 | 诊断 | 例子 |
|---|---|---|---|
| 槽位从未开启（合法省略） | `null`（或空列表） | 无 | 裸 `return`、`func f(x)`、`var x` |
| 合法空列表/尾逗号 | 空列表/正常节点 | 无 | `f()`、`f(1,)`、`{}`、`[1,2,]` |
| 已开启但零消费（内容完全缺失） | `ErrorExpression(MISSING, slotName, "", zeroWidth)` | 一条槽位诊断（outcome 为 `Missing` 时由槽位宿主补发，§3.4） | `func f(x=)`、`var x =`+换行、`{"a":}` |
| 已开启且有消费（坏 token 片段） | 可结构化的部分节点（内部含 marker），或 `ErrorExpression(ERROR, "ERROR", fragment, consumedSpan)` | 内层根因诊断（槽位宿主不补发泛化消息，§3.4） | `var hp=1+`（BinaryExpression+MISSING 右槽）、`return 1+`、`@export_range(0,1+,2)` |
| 类型槽位（`TypeRef` 非 Expression，无法持节点） | `null`，**无诊断**（D7：与 vendor 一致） | 无 | `func f(x:)`、`var x:;`、`signal s(x:)` |

机械判定规则（消除主观性）：

- 「消费」不由游标比较判定，而由 outcome 变体承载（§8.2.2）：`Missing` = 零消费且未推诊断；`Failed` =
  已推诊断、无节点（通常有消费；唯一的零消费来源是深度预算拒绝）；`Damaged` = 有部分节点且已推诊断。
  槽位宿主按变体机械选择 MISSING 零宽 / ERROR 片段 / 保留节点。
- **marker 的右端/锚点统一取构造当时的 `extentEnd()`**（最后消费 token 末端，含尾部 lambda 的特例调整，
  `GdScriptParser.java:263-265`），不取 `previous.endByte()`——尾部 lambda 实参场景两者可不同
  （`var x = f(, func(): return 1\n` 中 `previous.endByte()` 会越过 lambda 消费调整点），而父节点 range 均按
  `extentEnd()` 计算，同一基准保证 I3 包络（二审 C-F1）。
- **ERROR span = `[slotStart, extentEnd())`**，即实际消费区间，不含尾随空白；**MISSING = `[extentEnd(), extentEnd())`** 零宽。
- `return` 特判：值槽只在「`return` 后非语句尾/lambda 内跟真实 token」时开启（`GdScriptParser.java:1230-1240`）。
  outcome 为 `Missing`（零消费，如 lambda 内 `return` 后接 `,`/`)`、`return =3` 的 `=`）保持 `value=null`
  且**不新增诊断**，垃圾 token 仍由既有 `endStatement`/`synchronize` 路径处理（一审 A-2）；`Failed` 填
  ERROR 片段，`Damaged` 保留节点。`return =3` 的受损身份由 §7 第 1 条的诊断重叠门禁（判据 b）表达。

### 2.3 节点语义纪律（调研 §7.3）

- 不用 `ErrorExpression` 填 `TypeRef` 槽位；不用 `UnknownExpression` 表达语法错误。
- 不把整个受损声明替换为 `ErrorStatement`：健康名字、类型、body、前缀保留在原节点上。
- 正常 `null` 只留给真正 absent 的槽位。
- marker 的 `nodeType`：MISSING 用点分槽位名（词汇表见 §8.2.2），ERROR 片段用 `"ERROR"`，
  对齐 mapper 既有惯例与跨路径诊断可比性。

## 3. 设计不变量（终止性与同步边界护栏）

### 3.1 I1 控制流守恒（sealed-outcome 版）

- marker 构造不消费任何 token（不调用 `advance`/`match`/`consume`）。
- **Pratt 停折语义守恒**：循环头把「`left == null` 停折」扩展为「`left` 非 `Ok` 停折」，其余三个子条件
  （`infix() == null`、`lambdaEnded`、`stopOnAssign && EQUAL`）原样保留；受损节点携带部分结构上上浮到宿主，
  但不再参与折叠——控制流与今天的 null 逐点等价。
- 列表循环（实参/数组/字典/注解/pattern）推进仍由 `match(COMMA)`/闭合 token 驱动，marker 只追加列表，
  终止性与现状相同。
- C2 存活位点（assert/const/match 值）失败后消费**成功路径**的完整尾部（比今天提前 return 多消费 `)`/
  `endStatement`），这是有意行为改进，逐点论证见 §5.2。
- 语句分派 D：`Missing` 走今天的 null 路径逐字不变；`Damaged` 仅在 `isStatementEndToken() || lambdaEnded`
  门控下存活（§8.2.4；`isStatementEnd()` 在 lambda 内因 `inLambda` 恒真，不能作门控）。

### 3.2 I2 语句级推进

`parseSuiteImpl` 熔断只统计 null 语句（`lastStatementWasNull`）。本计划改动后：语句分派要么返回消费了
≥1 token 的节点（存活），要么返回 null——`Missing` 路径在非 lambda 下由分派点 `advance()` 推进；`Failed`/丢弃的
`Damaged` 路径在非 lambda 下一律按 EOF 守卫 `advance()` 一次（含深度预算置位、零消费的情形：毒区扫描的
行首停止点只停不消费，不先推进会在同一 token 上零消费空转至熔断；先推进只吃掉触发行的内容 token，
毒区扫描照常消费后续行）；**lambda 内这三条（`Missing`/`Failed`/丢弃的 `Damaged`）都只置
`lambdaEnded`、不 advance**（suite 循环条件含 `!lambdaEnded`，置位即停，不存在空转；先推进会吃掉
`synchronize` 本应停在其前的同步关键字）。`parseSuite`/`parseLambdaLike`/`parseNestedClass`/`parseMatchPattern`/`parseType`
的预算拒绝不经语句分派 D 路径——它们不 advance，把 NEWLINE/INDENT 留给外层 `synchronize`
（`rejectedSuiteLayoutDoesNotLeakIntoOuterScopes` 依赖的是这条路径，不得改成先推进）。熔断继续作为
兜底（口径见 §8.2.4）。

### 3.3 I3 范围包络

所有节点满足 `parent.startByte <= child.startByte && child.endByte <= parent.endByte`；零宽子节点恰好落在父端点合法。
marker 范围统一以**该侧失败点**的 `extentEnd()` 为基准（§2.2）；字典 entry 范围按
`[min(keyExtentStart, 各组件与 marker 起点), 构造时 extentEnd())` 重算，消除现行 value 侧越界
（调研 §5.5）、key 侧左边界越界（二审 C-F2）与分组 key 括号丢失（三审 C）。

### 3.4 I4 槽位诊断归属（outcome 机械化版）

**槽位宿主只在 outcome 为 `Missing` 时补发本槽位的泛化消息**；`Failed`/`Damaged` 已在内部推过根因诊断，
补发即双报。该规则由 `absorbSlot` 统一实施（§8.2.2），等价于原「诊断计数快照」规则但无时间窗：
`f(g(1+))` 的内层诊断属于内层槽位，外层调用实参槽收到的是 `Damaged`，不补发。缺闭合符诊断（2C）与
槽位诊断独立，不受抑制。

## 4. 行为需求（按槽位）

> 机制设计见 §8.2/§8.4/§8.5；本节只规定各位点的**目标行为**（实施步骤在 §9）。行号为现行值。

### 4.1 表达式族位点

| # | 位点 | 位置 | 目标行为 |
|---|---|---|---|
| E1 | Pratt 核心 | 2576-2641 | `parseExpression`/`parsePrecedence` 返回 `ParseOutcome<Expression>`；前缀无规则 → `Missing`（零消费、不推诊断）；深度预算 → `Failed`（诊断已推）；循环头非 `Ok` 停折 |
| E2 | 终端前缀 | 2643-2657 | literal/identifier/self → `Ok`；lambda 前缀 → `Ok`（内部损伤被自身槽位吸收） |
| E3 | 一元/二元/`not in`/三元/赋值 | 2943-3085 | 子槽失败 → `absorbSlot` 填 marker 建成现有节点（BinaryExpression/UnaryExpression/ConditionalExpression/AssignmentExpression），右端 `extentEnd()`；任一子槽非 `Ok` → 整节点 `Damaged`；消费顺序不变（三元 condition→`else`→false 照走） |
| E4 | cast/`is`/`?` | 3029-3094 | 维持现状（类型缺失返回 left，诊断已存在）→ `Ok(left)` |
| E5 | 分组 | 2662-2671 | 内层 `Ok` → 由分组自己处理 `)`：有则消费，缺则发 2C 零宽诊断（不算槽位损伤），均返回 `Ok(内层)`；内层 `Damaged` → `Damaged(内层)` 不消费 `)`；内层 `Missing`/`Failed` → `Damaged(slotMarker("grouping"))` 不消费 `)`，`Missing` 时补发 "Expected grouping expression." |
| E6 | 数组 | 2675-2695 | 失败元素填 marker（`"array.element"`）追加进列表；数组恒 `Ok`（吸收宿主）；缺 `]` 走 2C |
| E7 | 字典 | 2707-2797 | key/value 分侧 marker（`"dictionary.key"`/`"dictionary.value"`），删除 `nullPlaceholder`；Lua 风格名归一化只对 `Ok` key 进行（marker/受损 key 不再触发额外类型抱怨，I4）；双侧 `Missing` 仍丢 entry；entry 范围按 §3.3 重算；缺 `}` 走 2C |
| E8 | getNode | 2807-2865 | 6 个失败位点 → `Failed`（消费过、诊断已推、无节点）；成功 → `Ok` |
| E9 | preload | 2870-2885 | 路径槽 `absorbSlot("preload.path")`；节点建成，路径非 `Ok` → `Damaged`；缺 `)` 走 2C |
| E10 | await | 2889-2897 | 操作数槽 `absorbSlot("await.operand")`；节点建成，操作数非 `Ok` → `Damaged` |
| E11 | super | 2907-2939 | 裸 `super` 无 `.`/`(` → 一条诊断 + `Damaged(superIdent)`；`super.` 缺名 → `Damaged(AttributeExpression(super,[MissingAttributeStep]))`；`super.name` 缺 `(` → `Damaged(AttributeExpression(super,[AttributePropertyStep]))`；实参受损 → `Damaged` 调用/链；范围右端 `extentEnd()` |
| E12 | 后缀链 | 3121-3208 | 缺名点号 → 报错 + 追加 `MissingAttributeStep`（点号末端零宽）+ 链标 `damaged`，**链继续**（`Missing` 末步使后续 `(`/`[` 的 `instanceof AttributePropertyStep` 折叠分派不命中 → 走 `finishChain` + 新 `CallExpression`/`SubscriptExpression`，形成折叠障碍）；实参/索引受损 → 走完既有清理（pop multiline、closer 处理）建成节点后**立即返回 `Damaged`，链内不再续折**（`foo(1+).bar` 的 `.bar` 留给外层停折/sync，与今天 null 上浮等价）；调用/下标节点与折进链的 `AttributeCallStep`/`AttributeSubscriptStep` 的右端统一取 closer 处理后的 `extentEnd()`（成功时等于 `)`/`]` 末端；closer 缺失且尾部为 lambda 时等于 lambda body 末端），`finishChain` 以末步该右端为链右端 |
| E13 | 实参列表 | 3227-3247 | `parseCallArguments` 返回 `ParseOutcome<List<Expression>>`：失败实参填 marker（`"call.argument"`）追加进列表，列表恒携带；有 marker/受损实参 → `Damaged(list)`（实参列表**不是**吸收宿主——损伤继续传播给调用节点并触发 E12 的建后即返）；缺 `)` 只走 2C（不算受损） |
| E14 | 下标索引 | 3165-3189 | 索引槽 `absorbSlot("subscript.index")`；索引非 `Ok` → 链 `Damaged`；缺 `]` 走 2C |

### 4.2 语句/声明槽位

| # | 槽位 | 位置 | 目标行为 |
|---|---|---|---|
| S1 | return 值 | 1227-1243 | §2.2 特判：`Missing` 保持 `value=null` 不新增诊断；`Failed` → ERROR 片段；`Damaged` → 保留节点；owner facet `return.value` |
| S2 | 变量初始化器 | 1950-1955 | `absorbSlot("variable.initializer")`；声明恒存活；owner facet 同名 |
| S3 | 参数默认值 | 2147-2150 | `absorbSlot("parameter.default")`；`=` 后缺失与从未写 `=` 可辨。**批次耦合**：marker 使 `defaultValue() != null` 影响 `defaultUsed` 判定（2314-2322），必须与 S12 同批 |
| S4 | 注解实参 | 1811-1821 | 失败实参填 marker（`"annotation.argument"`）追加进列表；缺 `)` 走 2C；owner facet `annotation.arguments` |
| S5 | if/while/for 条件、for iterable | 1327-1437 | `absorbSlot("if.condition"/"while.condition"/"for.iterable")`；语句**带 marker 存活**（C1，vendor 同样保节点）；owner frame 在进 suite 前 pop 暂存、建成时绑定 |
| S6 | assert 条件/消息 | 1446-1474 | `absorbSlot("assert.condition"/"assert.message")`；语句**存活并消费成功路径尾部**（C2，D10）；缺 `)` 走 2C |
| S7 | const 值 | 2120-2132 | `absorbSlot("constant.value")`；语句存活并 `endStatement`（C2，D10）；缺 `=` 仍返回 null（无表达式槽开启） |
| S8 | match 值 | 1496-1600 | `absorbSlot("match.value")`；语句存活（含缺 INDENT 早退路径，返空 sections 的 MatchStatement） |
| S9 | match pattern | 1678-1684 | 表达式 pattern 失败 → 填 marker（`"match.pattern"`）保留在 patterns 列表；`var` 绑定缺名仍丢弃（不变） |
| S10 | pattern guard | 1610-1614 | 写了 `when` 但失败 → `absorbSlot("match.guard")`（3A）；无 `when` 保持 null；冒号文案按 guard 非 null 分支（与「写了 when」同义） |
| S11 | 字典 pattern key | 1745-1765 | key 失败 → 有 `:` 且子 pattern 存活时 entry 以 marker/受损 key 保留；受损 key 无载体（无 `:`、子 pattern 被丢、或落入 `..`/openEnded 报错丢 entry 的分支）→ 置「受损 key 无家可归」标志；**循环推进规则不变**（仍只由 `match(COMMA)`/闭合 token 驱动，到不了 `}` 时按今天报缺闭合符，残余由 match section 的冒号恢复行跳过消化），建成时整字典 pattern 回退为 `ErrorExpression(ERROR, slice=[patternStart, extentEnd()))`（覆盖实际消费的前缀；健康兄弟 entry 一并损失，登记）；受损 key 不触发 key-only 警告（3A） |
| S12 | 参数顺序 | 2310-2338 | 删除三处 `continue`，报错后仍加入参数列表；rest 的 variadic 重建保留已解析 `defaultValue` 与 `parameter.range()`；重复名检查不变。**与 S3 同批** |
| S13 | elif 条件 | 1341-1353 | `absorbSlot("elif.condition")`；分支**保留**（带 marker 条件与健康 body），不再整条丢弃（3A） |
| S14 | enum 值 | 2225-2233 | 写了 `=` 但失败 → `absorbSlot("enum.value")`，且 `memberEnd` 扩展到 marker 末端（I3）；无 `=` 保持 null（3A） |
| S15 | 缺名点号 | 3131-3145 | 见 E12（MissingAttributeStep 障碍） |
| S16 | 语句分派 D | 1281-1302 | 见 §8.2.4（Missing→null 路径；Failed→丢弃；Damaged→`isStatementEndToken() || lambdaEnded` 门控存活） |

### 4.3 显式不改

- `parseType`/`parseNameAndType` 与 `:` 缺类型零诊断接受面（D7）；signal 参数缺名 `break`、signal 拒默认值检查
  （2180-2182 在 S3 marker 化后会把 `signal s(x = )` 识别为有默认值而报错——可接受的更严格识别，登记 §6.2）。
- match 缺 INDENT 的 body 归属（头部换行已消费时 `synchronize` 立即停，body 归外层 suite，不变）。
- `frontend.cst`/`frontend.lowering` 与 `CstToAstMapperErrorRecoveryTest`（旧路径，不在本计划）。

### 4.4 补全上下文能力迁移（Phase 3）设计

1. 新增 `src/main/java/dev/superice/gdparser/frontend/gdscript/CompletionContext.java`：与旧版同合同的 public record
   （`Kind` 五值 `MEMBER_ACCESS/IDENTIFIER/TYPE_POSITION/CALL_ARGUMENT/UNKNOWN`；`MEMBER_ACCESS` 必带 `receiverRange`，
   其余必不带）。放 `frontend.gdscript` 包（module-info.java:7 已导出，无需改模块描述）。旧
   `infra.treesitter.CompletionContext` 保留不动，GDCC 切换前双轨并存。
2. 新增包私有 `CompletionContextAnalyzer`（同包）。输入：跳过 ERROR token 的 token 流（`scanSkippingErrors` 同口径）、
   容错 AST、源文本、字节偏移。输出为「分类规则按序命中」，**顺序与旧分析器的最深优先语义对齐**：
   - 预备：offset clamp 到 `[0, sourceLength]`；`nodeAt(root, offset)` 按 range 包含下钻（`start <= offset <= end`，
     零宽子节点仅在 offset 恰好等于其位置时命中），得到节点路径；同时取 offset 所在/之前的 token。
     **token 流契约**：facade `tokenize` 是 raw 流（含 NEWLINE/INDENT/DEDENT，`GdScriptParserFacade.java:46-52`），
     分析器的所有扫描须按 parser 的**有效 multiline 上下文**过滤布局 token（四审/五审 C）：
     - 以一个小栈复现 parser 语义：表达式分组、调用、下标与签名参数表的 `(`/`[`/`{` 压入启用帧；
       **类型容器的 `[`/`]` 只跟踪配平、继承当前 multiline 状态，不自行启用**（parser 的 `parseType`
       不 push multiline，`var x: Array[\nInner` 的换行是硬边界）；**识别到 `func`（lambda）即压入
       禁用帧**（parser 无条件禁用，单行/多行 body 同样隔离）；其签名参数的 `(` 临时压入启用帧，
       **在消费 `)` 之前弹出**（与 `parseFunctionSignature` 的 `popMultiline` 同点）——`)`、`->` 返回类型与
       签名结束的 `:` 都已回到禁用帧，返回类型里的换行是硬边界；禁用帧保持到 lambda body 结束
       （有效缩进回退/外层分组闭合）时弹出。
     - body 边界按**有效缩进与上下文状态**判定，不要求 raw 流存在 INDENT：多行 lambda 嵌在开括号内时，
       raw tokenizer 可能已在 `func` 行记录缩进、body 首行不再产生 INDENT（`GdTokenizer.java:608-633`），
       parser 是靠外层 multiline 抑制 + lambda 禁用帧得到 body 有效 INDENT 的，分析器须复现同一状态机。
     - 布局 token 仅在启用帧内被跳过，在禁用帧（顶层、lambda body）内是硬边界——点号不接 multiline，
       所以禁用帧内点号后隔 NEWLINE 的名字**绝不**算作成员名。
   - **receiver 通用公式**（R1/R2/R3/R3b 共用）：receiver 末端 = **点号前最后一个非布局 token 的末端**（token 级定位，
     不是 `base.range().endByte()`——分组 receiver `(node).pa|` 的 base 被解包为内层节点，直接取 base.end
     会漏掉 `)`）；receiver 起点 = 链 extentStart（AST 存活时），AST 被丢时用**括号配平的
     primary/postfix 反向扫描**：从点号反向跳过配平的 `)`/`]` 组、名字、`$`/`%` get-node 前缀
     与调用/下标尾巴，覆盖 `(node)`、`$Child`、`items[0]`、`make()` 等 receiver，并遵守有效布局硬边界
     （对齐旧分析器 `receiverRangeBeforeDot` 接受 named 节点的口径）。
   - R1 链上名字：offset 落在某 step 的名字 token 内（名字 token = 位于 `step.range().startByte()` 处的 token；
     对 `AttributeCallStep`/`AttributeSubscriptStep` 这样取避免误覆盖实参区）→ `MEMBER_ACCESS`，
     `replaceableRange` = 该名字 token range，`receiverRange` 按通用公式。
   - R2 点号光标：offset 处的 token 是 `.` 且属于某 `AttributeExpression` 链 → `MEMBER_ACCESS`，`replaceableRange` =
     点号后名字 token（无则点号末端零宽），receiver 按通用公式。
   - R3 缺名末步（AST 存活）：链末步为 `MissingAttributeStep` 且 offset 落在 `[点号末端, 链末端]` → `MEMBER_ACCESS`，
     `replaceableRange` 零宽于 marker 位置，receiver 按通用公式。`super` base 即 `IdentifierExpression("super")`，
     receiver 覆盖 `super` token，GDCC 据此走词法超类候选。
   - R3b 成员访问 token 回退（不依赖 AST 存活；覆盖 EOF 处 `self.b.(`、`node.method|(` 等整条链被丢弃的情形）。
     两种形态，均以有效 multiline 上下文扫描：
     (i) offset 紧邻形态为 `.`（如 `obj.|`、`self.b.|`）或 `.` `(` 且光标在空首实参位（`self.b.(|`）→
     `MEMBER_ACCESS`，receiver 按通用公式的反向扫描分支；**replaceable 双向判定**：点号后紧邻的
     下一个 token 属于 `isNodeName` 集合且与点号间无布局 token 时（合法空白成员名 `a.b.| c`），replaceable =
     该名字 token 的完整 range，否则 = 点号末端零宽。
     (ii) offset 落在 `isNodeName` 集合的 token 内或恰在其末端，且该 token 的前一个非布局 token 是 `.`
     （`node.method|(`、`node.method|(1,` 的具名 callee）→ `MEMBER_ACCESS`，replaceable = 该名字 token 完整
     range，receiver 反向扫描。
     光标位置语义表：`self.b.|(` → R3b(i)；`self.b.(|` → R3b(i)；`self.b.(1|` → R7；
     链存活时（实参成功）`self.b.|(1)` → R2/R3。
   - R4 独立标识符：offset 在 `IdentifierExpression` 内且未被 R1-R3b 命中 → `IDENTIFIER`，replaceable = 其 range。
     **token 级兜底**：表达式恢复被丢弃后 AST 中可能不存在该节点（如 `target(na|me, 1+)`），此时 offset 落在
     identifier 形态 token 内或恰在其末端、且未被 R1-R3b 与类型上下文（R5 注）命中 → `IDENTIFIER`，
     replaceable = 该 token 完整 range，不依赖 AST 存活。
   - R5 类型名：offset 在**显式类型** `TypeRef` 内 → `TYPE_POSITION`——`:=`/`: =` 推断标记（parser 的
     `inferredTypeRef` 构造的覆盖 `:` 至 `=` 的 TypeRef）**不参与**类型判定，光标在其 `=` 末端或之后时按
     初始化器/默认值表达式上下文分类（如 `var x :=|` 是表达式上下文而非类型上下文）；replaceable = offset
     处的名字 token（覆盖点分链与
     `Array[...]` 容器，token 级定位）；`extends` 目标在 AST 中是 String 而非 TypeRef，由 token 级判定：
     `extends` 关键字后的名字 token 含 offset → `TYPE_POSITION`。**类型上下文优先**：应用 R3b/R4
     之前先做 token 级类型上下文判定，命中即 `TYPE_POSITION`（已有名字 → 替换完整名字 token；缺内层名字 →
     光标处零宽；`receiverRange` 必为 null）。判定**不以 TypeRef 存活或其 range 覆盖末尾点号为必要条件**：
     优先用存活 AST；AST 缺失或 TypeRef range 未覆盖末尾点号（`var x: Outer.|`）时，按有效
     multiline 边界与括号角色回扫已开启的类型槽（`:`/`as`/`is`/`extends`，以及函数/lambda 签名中的 `->`
     引导）及其点分名称/容器后缀。
     点分类型名（`var x: Outer.In|ner`、`Array[Outer.In|ner]`、`x as Outer.In|ner` 的 `Inner` 前有点号）
     同样先由本条判 `TYPE_POSITION`，不得落入 R3b(ii) 的成员回退。
   - R6 零宽类型槽（token 回扫）：offset 前一非布局 token 是**算符角色**的 `as`/`is` → 零宽 `TYPE_POSITION`——
     `as`/`is` 属于 `isNodeName`，点号后是合法成员名（`var x = obj.as |`），get-node 路径名同理（`$as`、
     `%is`、`$/as`）；角色判定（前一非布局 token 不是 `.`、`/`、`$` 或 `%`）与 R5 的类型上下文回扫共用，
     成员名/路径名角色的 `as`/`is`
     不开启类型槽。类型测试开启符是算符角色的 `as`，或算符角色的 `is`（可紧跟一个 `not`，即
     `is not`——回扫可越过这个 `not` 再穿过点分名与容器后缀；裸 `not` 不是开启符，`x not |`/`obj.not |`
     是 `not in` 或一元 `not`，不开启类型槽）。是 `extends` 且
     目标尚未开始（无名字 token 或光标在其前）→ 零宽 `TYPE_POSITION`；是函数/lambda 签名中的 `->` 且
     返回类型尚未开始 → 零宽 `TYPE_POSITION`；是 `:` 则仅在变量/常量/参数/signal 上下文（回扫序列形如
     `var|const name :` 或签名参数表内 `name :`）→ 零宽 `TYPE_POSITION`；**排除**函数体冒号（`:` 前为 `)`）
     与字典冒号。
   - R7 调用实参：token 级找最内层「实参区含 offset」的括号对（闭合或 EOF 未闭合均可），先做**括号角色判定**：
     `(` 前是 callee 形态 token（identifier/`)`/`]`/`.`）或特殊调用前缀 `super`/`preload`（token 级判定，
     不以 callee AST 存活为必要条件——`super(`/`preload(` 由 parser 建成 CallExpression/PreloadExpression）；
     **成员名判定用 parser 的两套名字政策**：独立 callee 名字是 `isIdentifier` 集合，点号后的成员名是更宽的
     `isNodeName` 集合（`obj.class(1|` 的 `class` 合法）——R7 与 R3b 的两种形态在点号后一律按
     `isNodeName` 判定成员名；
     **排除签名参数表**：AST 存活时按结构排除（FunctionDeclaration/**ConstructorDeclaration**/SignalStatement/
     LambdaExpression 的参数区），token 回扫排除（`(` 前 identifier 之前是 `func`/`signal` 关键字）在 AST
     存活与丢失时都适用——`func f(|`、`signal s(|`、`func _init(|` 都不是实参区。随后 R1-R6 未命中才生效
     （实参内的标识符已被 R4 抢走：标识符实参 → `IDENTIFIER`，字面量实参/空槽/逗号后 → `CALL_ARGUMENT`）
     → `CALL_ARGUMENT`，replaceable 零宽于 offset。
     受损成员调用内 R3b 与 R7 的分界：`(` 与光标之间无非布局 token（空首实参位）→ R3b `MEMBER_ACCESS`，
     否则 → R7 `CALL_ARGUMENT`。
     本路径覆盖 `target(`、`target(1,`、`target(1` 三种 EOF 无最终换行形态（E12/E13 存活后多数情形已有
     CallExpression 锚点，token 级回退保留为兜底）；`target(1` EOF 判为 `CALL_ARGUMENT` 是对旧分类器
     UNKNOWN 结果的有意改进，登记在 §6.2。
   - R8 兜底：字符串/注释/空白/空源/其他 → `UNKNOWN`。
   - 优先级冲突显式规则：缺名点号位（R3/R3b）压过实参区（R7）；光标深入受损 call 的实参区（越过空首实参位）
     才让 R7 生效。
3. `GdScriptParserFacade` 新增 `parseCompletionContext(String source, long byteOffset)`：内部 tokenize（跳 ERROR token）
   + 容错 parse + 分析；无状态、线程安全契约与既有入口一致。
4. 新增 `GdCompletionContextTest`（`frontend/gdscript` 测试包）：对旧 `CompletionContextTest` 的 21 个用例**逐条编号
   映射**（不许只写「镜像语义」），另加恢复驱动用例：`super.|`、`super.ba|`、`self.b.(|`、EOF 处
   `target(|`/`target(1,|`/`target(1|`、CJK/emoji/CRLF 坐标、无最终换行。
5. 旧路径弃用标注（D5，§8.3）在 Phase 3 交付后进行：为 `infra.treesitter` 的公开入口类加 `@Deprecated` 与
   迁移指引注释。

## 5. 终止性与同步边界风险分析

### 5.1 表达式失败传播路径（sealed-outcome 版）

失败以 `ParseOutcome` 变体沿 `parseSuffixChain`/`parsePrecedence` 上浮：循环头在非 `Ok` 左操作数处停折
（与今天 null 停折逐点等价），宿主按吸收/传播纪律落成 marker 或 `Damaged` 节点；到达语句分派时按 §8.2.4
门控。消费序列逐位点论证：传播父节点与成功路径同序（三元/二元/赋值均照原顺序解析子槽）；后缀链失败点
先走完既有清理（pop multiline、closer 处理）再建节点；列表循环由 `match(COMMA)`/闭合 token 推进。
`if f(,): pass`、`assert(f(,))`、`preload(f(,))` 等嵌套失败：内层 `Missing` 填 marker、宿主存活，
`synchronize` 不再介入这些位点（无 panic 尾差）——`panicMode` 仍由诊断触发，语句尾 `synchronize`
（1311-1312）照常，但当前 token 已是行尾/同步点，零消费退出。

### 5.2 存活变化的逐路径论证（取代原 super 门控分析）

唯一的「今天丢 → 存活」变化集中在语句分派 D 的 `Damaged` 门控与 C1/C2 存活：

- **D 门控 `isStatementEndToken() || lambdaEnded`**：
  - NEWLINE/SEMICOLON/EOF（非 lambda）：存活路径由 `endStatement` 消费结束符；今天 null 路径 `advance()`
    消费当前 token + 报错后由 `synchronize` 吃到行尾。两者之后同经 `panicMode → synchronize`，兄弟起点一致。
  - lambda 内：`f(func(): super., 1)`——受损 `super.` 在 `,` 处不满足门控，丢弃并 sync（`, 1)` 被吃），
    与今天 null 路径一致；`f(func(): super. + 1)` 同理在 `+` 处丢弃。lambda 内真边界（NEWLINE/EOF）存活的
    语句与健康语句的 `endStatement` 行为一致。
  - 多结束符 delta（原六审 A 登记项）保持：`endStatement` 吃整串连续 NEWLINE/SEMICOLON，
    `var f = func():\n\tsuper.;\n\tvar y = 1` 存活后 `var y` 留在 lambda body 且 `consume(DEDENT)`——
    本计划接受该变化（兄弟留在声明作用域、减少级联错误），由 T10 钉死。
- **C1（if/while/for）**：失败路径今天已消费整链（suite 照走），存活只改返回值，token 流不变。
- **C2（assert/const/match 值）**：失败路径今天少消费（assert 不吃 `)` 不调 `endStatement`、const 不调
  `endStatement`）。存活后按**成功路径**消费：对良形尾部（`assert(1+, "msg")`、`const A = 1+`、`match 1+:`）
  消费完全一致。但须登记**同步集早停差异**：今天的 `synchronize` 会在同步关键字（如 `FUNC`）前停下，存活
  路径则把同步关键字当作槽位内容继续解析——`assert(1+, func(): pass)` 今天在 `func` 前停、之后可能按独立
  func 语句解析，存活后 `func(): pass` 被解析为 assert 的消息槽。这是对消费与归属的**有意改变**（消息槽
  拿到结构、减少裸 sync 丢弃），同样情形还有跨行消息与连续结束符；垃圾尾部（`assert(1+ @`）存活后
  closer 诊断 + `endStatement` 报错 + `synchronize` 吃残余，比今天多一条 `endStatement` 诊断（D10 接受）。
- **super 三分支**（E11）：原 Phase 2 #2/#3 的专用门控公式被 D 门控与 Pratt 停折取代——`super. + 1` 中
  受损链在循环头停折，`+ 1` 不续折，语句级非边界丢弃；`return super.`、`var x = super.` 由宿主槽位吸收；
  `if super.:` 由 C1 存活。行为等价于原门控分析（含 lambda 内 `,`/`)`/`:` 情形）。

### 5.3 其余护栏核对

| 项 | 结论 |
|---|---|
| `synchronize()` 同步集与调用点（491-544） | 不改动；无新调用点 |
| `advance()` 越 EOF 抛异常（248-251） | marker 构造不 advance；锚点取 `extentEnd()`，不读 `current` |
| multiline push/pop 配平 | 所有改动位点逐路径保持：分组/数组/字典/实参/下标/preload/assert 的失败路径仍先 pop 再建节点；assert 存活路径 pop 位置与成功路径一致 |
| owner frame 配平 | 每个 `pushRecoveryOwner` 在有限步内被 `popRecoveryOwner` 配对（语法错误不抛异常，配对无异常逃逸路径）；`parse()` 重置栈 |
| 列表循环推进 | marker 只追加列表，推进仍由 `match(COMMA)`/闭合 token 驱动 |
| 嵌套深度预算 | 不涉及递归深度变化；深度失败返回 `Failed`（诊断已推） |
| 范围包络 I3 | T7 全量不变量测试守护；恢复节点右端统一 `extentEnd()`（E3/E11/E12），不新增豁免 |

回归护栏：`GdScriptParserErrorRecoveryTest` 的 `@Timeout(10s)` 与全部既有恢复用例保持通过（除 §6.2 登记项）；
非法语料验收三性质（终止、≥1 条 in-bounds ERROR、兄弟结构正确）继续成立并新增 marker 断言。

## 6. 验收细则

### 6.1 新增/更新测试清单（JUnit5，均走 `script/run-gradle-targeted-tests.sh`）

| 编号 | 位置 | 断言要点 |
|---|---|---|
| T1 | `GdScriptParserErrorRecoveryTest` 新增 | `return 1+` → `value` 为 `BinaryExpression(1, MISSING("binary.right"))`，诊断恰好 1 条（内层 `+` 根因）；裸 `return`、`return 1`、`call(func(): return, 1)`（lambda 内零消费不产 marker 不新增诊断）、`return =3`（保持 value=null，仅 endStatement 诊断）为控制例 |
| T2 | 同上 | `var hp = 1+` → value 为带 MISSING 右槽的 BinaryExpression；`var hp =`+换行 → MISSING 零宽于 `=` 末端；`var hp = $/!!`（getNode `Failed`）→ ERROR 片段；健康 `var mp = 2` 兄弟不变 |
| T3 | 同上 | `func f(x: int = 1+)` → 名/类型保留 + 受损默认值；`func f(x=)` → MISSING 零宽；`func f(x:=1)`、`func f(x)` 零诊断 |
| T4 | 同上 | `@export_range(0, 1+, 2)` → 实参 `[0, BinaryExpression(1,MISSING), 2]` 三位置保留；`@export_enum("A", ,"B")` → 中位 MISSING；合法注解零诊断 |
| T5 | 同上 | `[1,,3]` → 中位 MISSING；`{"a":}` → value MISSING 锚在 `:` 末端且包络自洽；`{:1}` → key MISSING 且 entry 起点前移到锚点；`{1+:2}` → key 为受损 BinaryExpression、entry start 断言；`{(a): 1}` 合法分组 key 的 entry 含 `(`（控制例，`GdScriptParserTest.java:1010-1017` 不回归）；`{:}` 双侧 Missing 仍丢 entry；`f()`/`f(1,)`/`[1,2,]` 零 marker 零诊断 |
| T6 | 同上（更新+新增） | 2C：`var hp = (1` → value 健康 Literal(1) + 缺 `)` 零宽诊断带 code `parse.missing_closer.grouping` 且 `recovery` 绑定该 VariableDeclaration/facet `variable.initializer`；`var cb = (func(): return 1` 绑定外层变量、`var cb = func(): return (1` 绑定内层 return（归属边界）；`target(1` EOF → CallExpression 存活 + 缺 `)` 诊断（现状锁定）；诊断不跨 serde 持久化的限制写入文档（§7 第 8 条） |
| T7 | 新增范围不变量测试 | 恢复语料 + 合法 fixture 全 AST 递归断言 I3 包络（零宽等于父端点合法），含尾随空白/EOF/垃圾前缀用例与 `var x = f(, func(): return 1\n` 的 `extentEnd()` 特例；不许新增豁免 |
| T8 | `AstSexprSerdeTest` 新增 | 含 ERROR/MISSING marker 的 AST serde 往返相等 |
| T9 | `GdScriptParserTest` 更新+新增 | `self.b.(1)` → `CallExpression(callee=AttributeExpression(self,[b,Missing]))` 不折进 `b`；`self.p.[0]` 同理；`a.b(1)`/`a.b[0]`/`a. b`（空白合法）健康链形状不变；`a..(1)` 仍按单 token `..` 报错不误判 |
| T10 | 同上 | `node.` EOF → `AttributeExpression(node,[Missing])`；`super.`/`super.ba` 在语句终止位存活；`super. var y = 1`（同行垃圾仍吞）、`super.ba + 1`（不续折）、`f(func(): super. + 1)`（停折不消费）、`f(func(): super., 1)`（`, 1)` 仍被 sync 吃掉，与今天一致）；lambda 单 NEWLINE 归属控制例与多结束符有意 delta（§5.2）逐条钉死 |
| T11 | ~~删除~~ | D7 接受 `:` 缺类型零诊断，原 T11 不实施 |
| T12 | 同上 | `func f(a: int = 1, b: int)` 两参数均在列表 + 顺序诊断保留；`func f(...args = 1+)` rest 带坏默认值：variadic 重建保留 default marker 与参数 range；含 lambda 默认值的 rest 参数范围不倒挂；`func f(...args = (1` 的缺 `)` RecoveryLink 绑到**重建后**的最终 Parameter（owner 身份可从最终 AST 到达，按 `assertSame` 语义校验） |
| T13 | 既有恢复测试 | 全部通过（除 §6.2 登记项）；`@Timeout` 不触发；嵌套坏调用 `f(g(1+))`、lambda 默认值、`lambdaEnded` 边界、深度预算用例不回归 |
| T14 | parity 测试组 | `GdParserParityTest`/`GdParityReferenceTest`/`GdFixtureParityAuditTest` 全绿（合法语料不进恢复分支） |
| T15 | `GdCompletionContextTest` 新增 | 旧 `CompletionContextTest` 21 用例逐条编号映射并通过（成员前缀/裸点 EOF/missing 占位/点上光标/多级链/链基名/调用步骤方法名/左片段 receiver/语句前缀/`(`/空参/逗号/三种类型位置/字符串/空白/空源/越界 clamp） |
| T16 | 同上 | `super.\|`/`super.ba\|` → `MEMBER_ACCESS` 且 receiver 覆盖 `super`；受损链光标分位：`self.b.\|(` → R3b、`self.b.(\|` → R3b、`self.b.(1\|` → R7、`self.b.(1,\|` → R7 token 级判定；具名 callee：`node.method\|(`、`node.method\|(1,` → R3b(ii) `MEMBER_ACCESS`，replaceable=`method`、receiver=`node`；复合 receiver：`(node).method\|(` → receiver=`(node)`、`$Child.method\|(` → `$Child`、`items[0].method\|(` → `items[0]`；`a.b.\| c(`（点号后合法空白成员名）→ MEMBER_ACCESS 且 replaceable 覆盖 `c` 而非零宽；`(node).pa\|` 分组 receiver 完整含 `)`；lambda 禁用帧：单行 `var x = g(func(): a.\n\tb\|\n)` 与无 raw INDENT 的多行变体中点号后跨换行的名字**不**认作成员（`b\|` 走 R4 token 兜底判 `IDENTIFIER`），分组内合法换行成员续接为控制例；丢链中的标识符：`target(na\|me, 1+)` → R4 token 兜底 `IDENTIFIER`，replaceable=`name`；
     丢链关键字成员：`obj.class\|(1+) + 2` → R3b(ii) `MEMBER_ACCESS`（点号后 `isNodeName` 判定） |
| T17 | 同上 | EOF 无最终换行：`target(\|`、`target(1,\|`、`target(1\|` → `CALL_ARGUMENT`；`var x: \|`、`x as \|`、`extends \|`（零宽 R6）、`extends Fla\|` → `TYPE_POSITION`；点分类型名优先于成员回退：`var x: Outer.In\|ner`、`var x: Array[Outer.In\|ner]`、`x as Outer.In\|ner` → `TYPE_POSITION` 且 replaceable=`Inner`、无 receiver；类型上下文不依赖 TypeRef 存活：`var x: Outer.\|` → `TYPE_POSITION` 零宽、`var x = target(value as Outer.In\|ner, 1+)` → `TYPE_POSITION` 且 replaceable=`Inner`、同例非点分 `value as In\|ner` → `TYPE_POSITION` 而非 IDENTIFIER；返回类型槽：`func f() -> \|`、`func f() -> Outer.\|` → `TYPE_POSITION` 零宽、`var x = target(func() -> Outer.In\|ner: pass, 1+)` → `TYPE_POSITION` 且 replaceable=`Inner`、无 receiver；`func f():\|`、`{"a":\|}` → 非 TYPE_POSITION；括号角色判定：`func f(\|): pass`、`signal s(\|)` → 非 CALL_ARGUMENT；`func f(x = target(\|)): pass` 默认值内真调用 → CALL_ARGUMENT；`super(\|)`、`preload(\|)` → CALL_ARGUMENT；`func _init(\|): pass` → 非 CALL_ARGUMENT；
     `obj.class(1\|)`、`obj.class(\|)` → CALL_ARGUMENT（关键字成员名）；`var x = obj.as \|`、
     `var x = obj.is \|` → R8 `UNKNOWN`（成员名角色不开启类型槽）；`x as \|`、`x is \|` → `TYPE_POSITION` 控制例；`x is not \|`、`x is not In\|ner`、`x is not Outer.In\|ner`
     → `TYPE_POSITION`（末例 replaceable=`Inner`、无 receiver，不得落入 R3b）；`x not \|`、`obj.not \|`
     → 非 TYPE_POSITION；类型容器换行：`func f():\n\tvar x: Array[\n\tIn\|ner` → R4 `IDENTIFIER`
     （容器 `[` 不启用 multiline，换行是硬边界）；签名参数表控制例 `func f(x: Array[\n    In\|ner\n]): pass`
     → `TYPE_POSITION`；返回类型换行控制例 `g(func() -> Array[\n    In\|ner)` 与 `func f() -> Array[\n    In\|ner`
     → R4 `IDENTIFIER`（启用帧在 `)` 前弹出，`->` 期处于禁用帧）；get-node 路径名 `$as \|`、`%is \|`、`$/as \|`
     → 非 TYPE_POSITION；推断标记控制例：`var x :=\|`、`var x : =\|`、`func f(x :=\|): pass`
     → 非 TYPE_POSITION（按表达式上下文分类） |
| T18 | `GdScriptParserErrorRecoveryTest` 新增 | C1：`if 1+: pass`、`while : pass`、`for x in : pass` 语句带 marker 存活且 body/兄弟归属不变；C2：`assert(1+, "msg")`、`assert(true, 1+)`、`const A = 1+`、`match 1+:\n\t1: pass` 存活且消费尾部；C2 同步集早停差异：`assert(1+, func(): pass)` 的 `func(): pass` 进消息槽（今天 sync 在 `func` 前停）；熔断口径：`func f():\n` + 150 行 `)` 仍报 Too many statement errors，另加健康/失败语句交错用例（累计计数不被健康语句重置、第 102 次 null 触发） |
| T19 | 同上 | 3A：`match x:\n\t1 when : pass` guard marker；`enum E { A =, B }` value marker + memberEnd 包住 marker；`if a: pass\nelif : pass` 受损 elif 保留 body；`match x:\n\t{1+: 1}: pass` 受损 key 保留 entry；`match x:\n\t{1+}: pass` 无载体整体 ERROR 回退且 key-only 警告不发；`match x:\n\t{1+, 2: 3}: pass` 混合 entry 整 pattern 回退；`match x:\n\t{1+ var x: 3}: pass` 无逗号尾部按逗号循环停在 `var`，回退片段覆盖 `{1+`、缺 `}` 诊断照发、残余由 section 冒号恢复消化（勘误：原写的 `{1+ 2: 3}` 中 `1+ 2` 折叠为健康二元 key，不触发该路径，实施时按机制语义替换输入） |
| T20 | 同上 | 协议定向：`foo(1+)\nvar y`（停折+兄弟存活）、`a[1+]+2`、`(1+)+2`、`foo(1+, [2+])`、`foo([1+])`（吸收宿主不冒泡）、`foo(1+).bar` 与 `foo(1+)(2)`、`a[1+][2]`（受损实参/索引后链内停折，后续 suffix 不消费）、`foo(1+, func(): pass)`（lambda 边界）、`var x = func(a = 1+): pass + 2`（`+ 2` 不折进 lambda）、`super(1+)+2`（停折）、`var x = ()` 与 `var x = (\n`（分组纯 Missing 分支，不消费 `)`）、`var x = a = 1`/`var x = a =`/`return a =`（赋值 `!canAssign` 降级：不建 AssignmentExpression、`a =` 的 RHS Missing 改发 Failed 不双报）、深度预算边界含空白（不抛异常、I3 成立） |
| T21 | 同上 | serde 往返覆盖新形状（带 marker 的调用/链/三元/字典 pattern 回退片段） |

### 6.2 需要更新的既有测试（登记理由）

| 测试 | 现状断言 | 更新理由 |
|---|---|---|
| `GdScriptParserErrorRecoveryTest.java:152-163`（`var x =`）、165-176（`var x = 1 +`）、182-228（未闭合分组/数组/字典/调用）、243-272（`node.`） | `value==null` / 裸 `IdentifierExpression` / 精确诊断计数 | 新目标行为：marker/部分节点、缺名末步、I4 抑制级联泛化消息、2C 零宽诊断坐标（218-227 的 previous-token 坐标断言按新政策改写为 extentEnd 零宽，登记原因） |
| `GdScriptParserTest.java` 596-620（参数错误）、703-714（注解）、865-877（分组/容器空槽）、879（`foo(,)`）、922-1045（容器恢复）、1162-1174（superErrors）、1618-1650（悬空链误折叠）、2386+（match 边界）、2613（lambda 裸 return 注记） | 丢参/压缩实参/折叠到前一属性/丢构造 | 同上；逐条改写为新形状断言，禁止仅放宽断言 |
| `GdScriptParserFacadeTest.java:42-139`（tolerant/strict/并发快照） | 精确诊断列表 + 序列化 AST 快照 | 诊断带 code、AST 含 marker，按新形状重拍快照 |
| super 相关既有断言 | `super.` 2 条 ERROR | 存活后诊断数变化（单条带 code）；登记并改写 |
| 旧补全分类器 `target(1` EOF → UNKNOWN | `CompletionContextTest` 行为 | 新路径有意改进为 `CALL_ARGUMENT`；**旧测试不动**，仅在新测试登记差异 |
| `CstToAstMapperErrorRecoveryTest` | tree-sitter 路径 | **不改**：旧路径行为不在本计划范围 |

### 6.3 整体验收门禁

1. `script/run-gradle-targeted-tests.sh --tests <上述测试类>` 全绿。
2. `./gradlew clean build --no-daemon --info --console=plain` 全绿。
3. 可选观察驱动：刷新 `tmp/probes/PureJavaRecoveryResearchProbe.java` 核对 48 例新形状（tmp 目录允许修改）。
4. GDCC 联动验收（§7）在 GDCC 仓库另行实施与验收。

## 7. GDCC 侧合同点（本仓库不实施，仅记录更改方向）

1. **受损构造门禁（双判据）**：构造 C 受损当且仅当 (a) C 的子树含 `ErrorExpression`/`MissingAttributeStep`，或
   (b) 存在与 C 的 range 相交的 parser ERROR 诊断。(b) 兜住无 code/无 link 的旧诊断；WARNING 不计入。
   门禁在 `FrontendClassSkeletonBuilder`（gdcc `FrontendClassSkeletonBuilder.java:768-814`）填成员**之前**消费：
   含受损默认值/初始化器/注解实参的构造不发布可信 arity、不投影注解元数据、不进有效 registry；健康兄弟照常发布。
   **不得把判据 (b) 推广为唯一真源**（迁移报告 §8.1 护栏）：range 包含健康 body 的构造不能只按诊断相交整条跳过。
2. **RecoveryLink 优先（2C）**：带 `recovery` 的诊断按 owner/facet 精确定位受损构造（缺闭合符类不再依赖
   range 相交猜归属），优先级高于判据 (b)；owner 身份只在同代结果内有效（§7 第 8 条）。
3. **参数保留**：顺序非法参数仍在列表；`defaultValue` 为 marker/受损节点的签名不完整——不做可信
   overload/arity 选择，保留 body binding，不报虚假缺参错误（`func f(x: int = 1+)` 的 `f()` 不再报
   missing required parameter）。
4. **诊断 owner 去重**：parser 内 I4 保证单槽位单根因；跨阶段（loop control、nested cast、坏 return 的派生
   sema 报错）由 GDCC 以**稳定 `code`** 做 owner 关联（有 code 按 code；`code == null` 退回范围重叠判据，
   不按范围相等或消息前缀去重——nested cast 两诊断范围本就不同）。GDCC analyzer 对手工 AST 的独立检查
   合同保留。
5. **marker 感知的 null 语义**（3A 后必读）：`MatchSection.guard != null` 不再等价于「guard 健康」
   （可能是 `match.guard` marker）；`EnumMember.value != null` 可能是 `enum.value` marker——坏 enum 不发布
   推测数值，也不从受损值继续推导后续自动值；受损 elif 的 `ElifClause.condition` 同理。消费所有「null =
   合法缺席」判断点前须先判 marker。
6. **补全切换**：`FrontendSnapshotCompletionService.java:88` 的 `COMPLETION_FACADE` 切换为
   `GdScriptParserFacade.parseCompletionContext`；`sanitizedReplaceableRange` 净化保留至新接口有覆盖测试；
   `superMethodCandidates`（:337-367）经 receiver=`super` 事实解析恢复词法超类候选：`super.`/
   `super.ba` 的 receiver 即 `AttributeExpression(IdentifierExpression("super"), …)`（E11 存活形状）。
7. **同代身份**：补全上下文只携带 kind/range，不携带 AST 节点身份；GDCC 不得把新 AST 节点身份写入旧快照
   side table。
8. **同代消费要求**：`RecoveryLink` 指向同代 AST 节点对象，不随 serde 持久化；GDCC 须在直接 parse 结果上
   消费（反序列化后的 AST 须重新绑定或拒绝沿用 link，不能用结构相等冒充身份）。
9. **super/成员候选词法口径**：super 候选仍只枚举词法超类方法（不按 super 表达式的当前类类型）；
   含 marker 的调用实参列表保留位置（`target(1,` → 第三槽位可从 marker 位置推断 active argument）。

## 8. 架构级设计（8.1/8.2/8.4/8.5 已定，8.3 已定）

### 8.1 诊断结构演进：code + RecoveryLink（已定）

用户已决策（2026-10-08 无 API 稳定需求；2026-10-14 采纳 2C 扩展）：

- **`AstDiagnostic` 增加第 5、6 组件** `@Nullable String code` 与 `@Nullable RecoveryLink recovery`，保留 4 参
  兼容构造器（`code = null, recovery = null`）供旧 tree-sitter 构造点与未分配位点继续使用——现行 4 参构造点：
  `CstToAstMapper.java:1093-1098,1185,1367`、`AstFactory.java:24,28`、本 parser 的 `pushError`/`pushWarning`。
- **`RecoveryLink(@NotNull Node owner, @NotNull String facet)`**（新 record，`frontend.ast`）：同代的
  「诊断 → 受损构造」关联；facet 与 marker 槽位词汇共用点分命名（§8.2.2）。
- **分配范围**：本计划触及的诊断位点必须分配稳定 code（词汇表 §8.2.2/§8.4，随 Phase 0 登记进
  `doc/godot-java-parser-implementation.md`）；`recovery` 只在缺闭合符类诊断上出现（§8.4）。
- `AstMappingResult` 维持 `(ast, diagnostics)` 两组件不变；serde 不受影响（`AstSexprSchema` 未注册
  `AstDiagnostic`；`RecoveryLink` 不同代持久化，§7 第 8 条）。
- GDCC 诊断 owner 去重按 §7 第 4 条。

### 8.2 sealed-outcome 恢复协议（D6，取代身份集合方案）

#### 8.2.1 位点分类

沿用原分类（按「null 在今天的控制流角色」），编码改为 outcome：

| 类别 | 位点 | 原行为 | 新编码 |
|---|---|---|---|
| A（宿主存活槽位） | 变量/参数默认/注解/数组/字典列表槽 | 宿主存活、槽位 null | `absorbSlot` 填 marker（return 例外，走 S1 三路开关） |
| B（表达式内部传播点） | 分组/实参/下标/操作数/await/preload/super | null 上浮 = 停折信号 | 非 `Ok` outcome 上浮 = 停折信号（§8.2.3） |
| C1（失败后仍消费整链） | if/while/for 头部 | 吃完 suite 返回 null | 带 marker 存活（S5） |
| C2（失败提前返回） | assert/const/match 值/match pattern | 提前 return null | 带 marker 存活 + 消费成功路径尾部（S6-S9，D10） |
| C3（宿主存活仅丢槽） | guard/enum 值/elif/字典 pattern key | 置 null/丢槽 | 槽位 marker（S10/S11/S13/S14，3A） |
| D（语句级分派） | 表达式语句 1281-1302 | null → drop+熔断 | 按 outcome 门控（§8.2.4） |

lambda 前缀恒 `Ok`（内部损伤被签名默认值槽与 body 语句自身吸收）；`cast`/`is`/`?` 失败返回 left
（诊断已存在）编码为 `Ok(left)` 的降级成功。

#### 8.2.2 `ParseOutcome<T>` 类型与机械化规则

新建包私有密封接口 `frontend/gdscript/ParseOutcome.java`：

```java
sealed interface ParseOutcome<T> {
    record Ok<T>(T value) implements ParseOutcome<T> {}        // 健康节点
    record Missing<T>() implements ParseOutcome<T> {}          // 零消费失败，未推诊断
    record Failed<T>() implements ParseOutcome<T> {}           // 已推诊断，无节点（通常有消费；深度预算拒绝时为零消费）
    record Damaged<T>(T value) implements ParseOutcome<T> {}   // 部分节点，已推诊断
}
```

- **诊断携带合同**：`Missing` 由槽位宿主补发本槽位上下文消息（I4）；`Failed`/`Damaged` 的根因已在内部
  推过，宿主不得再发泛化消息。`valueOrNull()`/`valueOrThrow()` 供确知携带值的宿主（如实参列表）使用。
- **`slotMarker(outcome, slotStartByte, slotName)`**：`Missing` → `ErrorExpression(MISSING, slotName, "",
  [extentEnd(), extentEnd()))`；`Failed` → `ErrorExpression(ERROR, "ERROR", slice, [min(slotStart, end), end))`
  其中 `end = extentEnd()`——`Failed` 允许零消费（深度预算拒绝发生在任何消费之前，`extentEnd()` 可以落在
  `slotStart` 之前），此时退化为 `[extentEnd(), extentEnd())` 零宽片段，不构造倒置区间（`SourceTextIndex`
  拒绝 `start > end`）。不触碰游标。
- **`absorbSlot(outcome, slotStartByte, slotName, contextMessage)`**：`Ok`/`Damaged` → 返回节点；
  `Missing` → 补发 contextMessage（带 code）+ MISSING marker；`Failed` → ERROR 片段。统一实施 I4。
  **例外**：`return` 值槽不走 `absorbSlot`（S1 特判：`Missing` 保持 null 且不诊断）；`parseCallArguments`/
  下标索引自行展开 switch（损伤须传播给调用节点，E12/E13）。
- **marker 槽位词汇**（MISSING 的 `nodeType`，同时是 `RecoveryLink.facet` 的候选值）：
  `variable.initializer`、`constant.value`、`parameter.default`、`return.value`、`annotation.argument`、
  `array.element`、`dictionary.key`、`dictionary.value`、`call.argument`、`subscript.index`、`grouping`、
  `binary.right`、`unary.operand`、`ternary.condition`、`ternary.else`、`assignment.value`、`await.operand`、
  `preload.path`、`assert.condition`、`assert.message`、`match.value`、`match.guard`、`match.pattern`、
  `dictionary_pattern.key`、`enum.value`、`if.condition`、`elif.condition`、`while.condition`、`for.iterable`。
- **诊断 code 词汇**：`parse.missing_closer.{grouping,array,dictionary,call,subscript,annotation,preload,assert}`、
  `parse.missing_expression.{variable_initializer,constant_value,parameter_default,annotation_argument,
  array_element,dictionary_key,dictionary_value,call_argument,subscript_index,grouping,binary_right(按操作数),
  unary_operand,ternary_condition,ternary_else,assignment_value,await_operand,preload_path,assert_condition,
  assert_message,match_value,match_guard,match_pattern,dictionary_pattern_key,enum_value,if_condition,
  elif_condition,while_condition,for_iterable}`、`parse.attribute.missing_name`、`parse.super.{missing_access,
  missing_name,missing_call}`、`parse.signature.{mandatory_after_optional,rest_with_default,parameter_after_rest}`。
  集中在包私有 `DiagnosticCodes` 常量类。

#### 8.2.3 传播、停折与后缀链

- **Pratt 停折点唯一**：循环头在「`left` 非 `Ok`」时返回该 outcome（与今天 null 停折逐点等价），
  `infix()==null`/`lambdaEnded`/`stopOnAssign && EQUAL` 三子条件原样保留。前缀 `Missing`/`Failed` 直接
  上浮（不进入折叠）。后缀续折在链内判定，不回到循环头——链内规则分两种：缺名点号追加
  `MissingAttributeStep` 后**继续**（`self.b.(1)` 的 `.(1)` 与缺名点号在同一链调用内）；实参/索引受损则
  清理、建节点、**立刻返回 `Damaged`**，不消费后续 suffix（`foo(1+).bar` 的 `.bar` 留给外层停折/sync，
  与今天 null 上浮等价）。
- **传播父节点**（二元/`not in`/一元/三元/赋值/await/preload）：子槽 `absorbSlot` 后建成现有节点，
  任一子槽非 `Ok` → 整节点 `Damaged`；右端 `extentEnd()`。**赋值的 `!canAssign` 分支例外**（字典 key、
  实参、pattern 等禁止赋值上下文）：保持今天的降级——只推「Assignment is not allowed」诊断并直接返回
  右部 outcome，不建 `AssignmentExpression`；但右部为 `Missing` 时须改发 `Failed`（`a =` 已消费且诊断
  已推，透传 `Missing` 会让外层宿主误补泛化消息），右部 `Ok`/`Failed`/`Damaged` 原样透传。
- **分组**（E5 三分支）：内层 `Ok` 保持解包返回；`Damaged` 原样上浮；`Missing`/`Failed` 落成
  `Damaged(slotMarker)`；失败两分支均不消费 `)`（vendor 同）。
- **吸收宿主**（数组/字典/注解/语句槽位）：marker/受损子节点入槽，宿主恒健康（`Ok`/存活），
  不向外层冒泡——`foo([1+])`、`[1+, 2] + 3` 的折叠行为与今天一致。**实参列表与下标索引不是吸收宿主**
  （E12/E13）：损伤传播给调用/下标节点并在链内建后即返。
- **后缀链**（E12）：缺名点号产 `MissingAttributeStep`（点号末端零宽）并标 `damaged`；Missing 末步使
  后续 `(`/`[` 不再折到前一属性 step（`self.b.(1)` → `CallExpression(callee=AttributeExpression(self,
  [b, Missing]))`）；实参/索引受损 → `Damaged`；调用/下标节点右端统一 `extentEnd()`（不是成功路径的
  `previous.endByte()`——尾部 lambda 场景两者不同，见 §2.2）。
- **super**（E11 三分支存活）：receiver 与已知名前缀保留在 AST 中供补全锚定。
- **getNode**：路径形状失败 → `Failed`（片段由宿主槽位落成 ERROR）。

#### 8.2.4 语句分派（D）与熔断口径

`parseStatement` 的表达式分支按 outcome 分派：

- `Missing`：今天的 null 路径逐字不变（lambda 内置 `lambdaEnded` 不消费；非 lambda `advance()` + 报错）；
  `result = null` 计入熔断。
- `Failed`：复用同一条 null 路径的状态转换，只省掉泛化诊断（根因已在内部推过）——lambda 内置
  `lambdaEnded`；非 lambda 一律按 EOF 守卫 `advance()` 一次（同步边界与今天逐 token 一致：`super. var y = 1`
  的 `var` 必须被吃掉，不能留给 sync 早停而解析成新声明；深度预算置位时同样推进，见 §3.2 的空转论证）；
  `result = null`，不调 `endStatement`，计入熔断。
- `Damaged`：**仅当 `isStatementEndToken() || lambdaEnded`**（真边界 NEWLINE/SEMICOLON/EOF 或已置位的
  lambda 虚拟边界）时存活为 `ExpressionStatement`（照调 `endStatement`、绑定 owner frame、**不计入**熔断）；
  否则按 `Failed` 同一条丢弃路径处理（lambda 置位 / 非 lambda 一律推进一次 + sync；不补诊断、不调
  `endStatement`，计入熔断）。
  注意 `isStatementEnd()` 在 lambda 内因 `inLambda` 恒真，**不能**作为门控——否则 `f(func(): super. + 1)`
  的受损语句会存活并让 `endStatement` 吃掉本不属于自己的边界；门控用真边界判定后，`+` 处仍丢弃并 sync，
  与今天一致。
- C1/C2/3A 存活语句返回节点，不计入熔断。熔断是**每个 suite 累计**的 `errorCount++ > 100`（第 102 次 null
  触发），健康语句**不**重置计数；阈值与 `nestingBudgetExceeded` 短路不变。

#### 8.2.5 C1/C2/C3 细则

- **C1（if/while/for）**：条件/iterable `absorbSlot`；owner frame 在进 suite **之前** pop 暂存（防止 body
  内闭合符诊断误绑头部 facet），节点建成时绑定；语句存活，suite/elif/else 流程不变。
- **C2（assert/const/match 值）**：D10——存活并按成功路径消费尾部；assert 的 multiline pop 位置与成功
  路径一致；const 缺 `=` 仍返回 null（无表达式槽开启）；match 缺 INDENT 仍早退（返空 sections 的
  MatchStatement，value 恒非 null 故不再整丢）。
- **match pattern**（S9）：表达式 pattern 失败填 `match.pattern` marker 留在列表（`var` 绑定缺名仍丢弃）；
  「No pattern found」检查以列表非空为准（marker 占位即见过 pattern）。
- **C3（3A）**：guard/enum 值/elif/字典 pattern key 按 S10/S11/S13/S14 填 marker；字典 pattern key 无载体
  分支整体回退为 `ErrorExpression(ERROR)` 片段（`DictEntry` 的 `List.of(key, value)` 不允许 null，且
  AST 无法表示受损 key 的 key-only 形态——不为受损 key 伪造未开启的 value 槽位）；key-only 警告只对
  健康 key 发出。

### 8.3 旧 tree-sitter 路径弃用（已定：本计划交付后标注弃用）

用户已决策（2026-10-08）：本计划 Phase 3 交付后，旧 tree-sitter 路径的公开入口标注弃用。

- **范围**：`infra.treesitter` 的公开 API——`GdParserFacade`（`parseCstRoot`/`parseSnapshot`/
  `parseCompletionContext`）、`CompletionContext`、`CompletionContextAnalyzer`、`GdParseSnapshot`。
- **方式**：源码级 `@Deprecated` 注解 + javadoc 指向新入口 `GdScriptParserFacade`；`architecture-overview.md`
  与 `godot-java-parser-implementation.md` 的角色描述同步改为「已弃用的旧路径」。
- **保留与移除**：`frontend.cst`/`frontend.lowering`（CstAdapter、CstToAstMapper 等）暂不标注——它们是
  `GdParserParityTest` 的合法源 parity 参照，随旧路径整体移除时再处理；parity/fixture 测试与
  `CompletionContextTest` 继续作为回归参照运行，弃用标注的 `@SuppressWarnings` 处理在实施时统一进行。
  移除时间表属 GDCC 完成切换（§7 第 6 条）后的后续工作，不在本计划。

### 8.4 缺闭合符诊断（2C，D8）

- **位点与 code**：分组（`parseGroupingExpression`）、数组、字典、调用实参（`parseCallArguments`）、下标、
  注解实参、preload、assert——共 8 处 closer，分别配 `parse.missing_closer.*` code。
- **形状**：零宽诊断 `[extentEnd(), extentEnd())`（失败点），`panicMode` 照常置位；不算槽位损伤
  （`parseCallArguments` 缺 `)` 不使列表 `Damaged`）。
- **归属**：诊断发出时记入**最内层打开的 owner frame**；frame 在槽位解析前 push、该槽位表达式解析完立即
  pop 暂存（先 pop 后进 suite，防止 body 诊断误绑头部 facet）、owner 节点建成时绑定（`RecoveryLink(owner,
  facet)`）；owner 未建成（语句被丢）的 frame 直接丢弃，诊断留 code+range 走 §7 第 1 条判据 (b)。
- **配对的位点细则**：
  - assert 的条件/消息 frame 在各自 `parseExpression` 后 pop；assert 自身的 `)` 闭合符诊断落在另一个覆盖
    整个实参区的 `assert.arguments` frame（条件/消息 frame pop 后它仍是最内层），建成 AssertStatement 时绑定。
  - 注解实参的 frame 绑到 `AnnotationStatement` 自身（facet `annotation.arguments`）——注解解析时还没有
    被注解的声明，不能绑给后到的声明。
  - 参数默认值的 frame **延迟到签名最终接纳该参数时再绑定**：rest 参数的 variadic 重建会替换 Parameter 节点，
    frame 随之迁移到重建后的节点；任何最终被丢弃的参数其 frame 一并丢弃（§7 第 8 条的同代身份要求 owner 必须
    在最终 AST 中可按身份到达）。
  - enum 值开 `enum.value` frame，绑到建成的 `EnumMember`；match pattern 列表开 `match.pattern` frame，
    绑到建成的 `MatchSection`。
- **归属边界用例**（T6）：`var cb = (func(): return 1` 的缺 `)` 绑外层变量初始化器；
  `var cb = func(): return (1` 的缺 `)` 绑内层 return 值——几何位置相同的两个诊断按 owner 栈区分。
- **owner facet 词汇**（开 frame 的位点）：`variable.initializer`、`constant.value`、`parameter.default`、
  `return.value`、`assert.condition`、`assert.message`、`assert.arguments`、`match.value`、`match.guard`、
  `match.pattern`、`if.condition`、`elif.condition`、`while.condition`、`for.iterable`、`enum.value`、
  `expression`（表达式语句）、`annotation.arguments`。
- **可达性清扫**：`parse()` 返回前按对象身份遍历最终 AST 建立可达 Node 集合，对 owner 不可达的
  `RecoveryLink` 清空 `recovery`（保留 code/range/message，不回绑外层 owner）——覆盖「延迟绑定后祖先
  构造仍被丢弃」的情形（如 `func f(x, x = func(): return (1` 的重复参数被丢后，内层 return 上已绑定的
  缺 `)` link 失效）。验收：T6/T12 统一断言每个非空 `recovery.owner` 都能从最终 AST 按身份（`assertSame`
  语义）到达。

### 8.5 C3 槽位 marker（3A，D9）

- guard/enum 值/elif 条件/字典 pattern key 的失败不再「置 null/丢槽」，按 S10/S11/S13/S14 填 marker；
  enum `memberEnd` 扩展到 marker 末端（I3）；elif 保留分支与健康 body。
- **null 语义变化的面**：`MatchSection.guard`、`EnumMember.value` 的 null 从「可能受损」收窄为
  「从未书写」；`ElifClause.condition` 本就非空——elif 的变化是**分支保留语义**（今天丢整条 clause，
  现在保留带 marker 条件与 body）。消费方须先判 marker（§7 第 5 条）。
- **字典 pattern key 回退条件**（粒度 = 整个字典 pattern，非单条 entry）：受损 key 且（无 `:` / 子 pattern
  为 null / `..`-as-value 或 openEnded 报错丢 entry）→ 置标志；**不引入新的跳 token 机制**——循环仍按
  逗号/闭合符推进，到不了 `}` 时按今天报缺闭合符，残余由 match section 的冒号恢复行跳过消化；建成时整
  pattern 回退为覆盖 `[patternStart, extentEnd())`（实际消费前缀）的 `ErrorExpression(ERROR)`
  （`DictEntry` 的 `List.of(key, value)` 不允许 null，且 AST 无法表示受损 key 的 key-only 形态——不为受损
  key 伪造未开启的 value 槽位）；有载体时 entry 以 marker/受损 key 保留；回退使健康兄弟 entry 一并损失
  （登记，T19 混合 entry 用例）。
- **serde**：全部复用已注册节点类型，无 schema 变化；损伤身份随 AST 往返保留。

## 9. 实施步骤（分批；B1-B6 同一编译单元，B6 末统一编译）

| 批次 | 内容 | 触及文件 | 验证 |
|---|---|---|---|
| B0 | 基础设施：`ParseOutcome`、`DiagnosticCodes`、`RecoveryLink`、`AstDiagnostic` 扩展；parser 助手（code 重载、`pushMissingCloser`、`slotMarker`、`absorbSlot`、owner frame 栈 + `parse()` 重置） | `frontend/gdscript/ParseOutcome.java`（新）、`DiagnosticCodes.java`（新）、`frontend/ast/RecoveryLink.java`（新）、`AstDiagnostic.java`、`GdScriptParser.java`（助手区） | `./gradlew classes --no-daemon --console=plain` |
| B1 | Pratt 核心：`PrefixRule`/`InfixRule` 签名、`parseExpression`/`parsePrecedence` outcome 化、循环头停折、终端前缀与 lambda 前缀 `Ok` | `GdScriptParser.java` | 同编译单元，不单独编译 |
| B2 | 传播父节点与前缀：分组/数组/字典（删 `nullPlaceholder`）/getNode/preload/await/一元/二元/`not in`/三元/cast/`is`/`?`/赋值（E3-E10、E14 的索引槽） | 同上 | 同上 |
| B3 | 后缀链与 super：E11/E12/E13（MissingAttributeStep 障碍、`extentEnd()` 右端、实参列表 outcome） | 同上 | 同上 |
| B4 | 吸收语句槽 + 参数顺序：S1-S4、S14（enum 值）、S12（参数顺序，与 S3 同批耦合） | 同上 | 同上 |
| B5 | C1/C2/C3：S5-S11、S13 | 同上 | 同上 |
| B6 | 语句分派 D（S16）+ 熔断口径核对 + `parse()` 返回前的 RecoveryLink 可达性清扫（§8.4） | 同上 | `./gradlew classes` + `script/run-gradle-targeted-tests.sh --tests GdScriptParserErrorRecoveryTest,GdScriptParserTest,GdScriptParserFacadeTest` 初跑（预期 §6.2 登记项失败） |
| B7 | 恢复侧测试：§6.2 逐条改写 + T1-T14、T18-T21 新增 | `src/test/...` | targeted 全绿 |
| B8 | 文档：`godot-java-parser-implementation.md` 词汇表（code/marker/facet）与产出政策、`architecture-overview.md:190` 政策行、本计划状态收尾 | `doc/` | 文档与实现一致性自查 |
| B9 | 恢复侧评审：review-expert-a + review-expert-c 并行审阅实现与文档，修复中/高/阻断项后复审 | — | 双 APPROVE |
| B10 | 补全迁移（§4.4）：`CompletionContext` DTO、`CompletionContextAnalyzer`（R1-R8 + token 级回退 + lambda 禁用帧）、`GdScriptParserFacade.parseCompletionContext`、T15-T17；随后执行旧路径弃用（§8.3） | `frontend/gdscript/CompletionContext.java`（新）、`CompletionContextAnalyzer.java`（新）、`GdScriptParserFacade.java`、`infra/treesitter/`（弃用标注） | `GdCompletionContextTest` 全绿 + parity 回归 |

每批内部遵守：marker 不消费 token；push/pop（multiline 与 owner frame）逐路径配平；新 `pushError` 一律带
code；改动位点的既有 doc 注释同步更新（政策表述类）。

## 10. 关键源码索引

| 文件 | 重点行号（现行值） |
|---|---|
| `frontend/gdscript/GdScriptParser.java` | 诊断 317-337；synchronize 491-544；suite 熔断 1102-1140；语句分派 1171-1315（表达式分支 1277-1302、return 1227-1243）；endStatement/lambda 661-687；if/while/for/assert 1325-1575；match 1494-1787；注解 1797-1825；变量 1916-1972；const 2112-2134；参数 2138-2153/2290-2358；enum 2204-2243；Pratt 核心 2474-2641；分组/数组/字典/getNode/preload/await 2662-2897；super 2907-2939；一元/二元/三元/赋值 2943-3094；后缀链/实参 3121-3247 |
| `frontend/gdscript/GdScriptParserFacade.java` | 25-63 现有入口（新增 parseCompletionContext） |
| `frontend/gdscript/ParseOutcome.java`、`DiagnosticCodes.java` | 新建（B0） |
| `frontend/ast/` | `ErrorExpression.java`、`MissingAttributeStep.java`、`AstDiagnostic.java`（扩展）、`RecoveryLink.java`（新）、`AstMappingResult.java` |
| `frontend/cst/CstIssueKind.java` | ERROR/MISSING 枚举（复用，无需 native runtime） |
| `infra/treesitter/CompletionContext.java`、`CompletionContextAnalyzer.java`、`GdParserFacade.java` | 迁移参照物（旧路径不动） |
| `frontend/serialize/AstSexprSchema.java` | 109/122/138 已注册错误/缺失节点 |
| `vendor/godot/gdscript_parser.cpp` | 3369-3404 attribute 失败保留 base；3450-3554 super/call completion hook；3255-3356 字典 dummy literal；1529-1555/1704-1792 参数（vendor 也跳参，本计划有意偏离）；2261-2293 assert 丢弃（本计划有意偏离） |
| GDCC 侧（仅合同点） | `FrontendSnapshotCompletionService.java:72-109,337-367`；`FrontendClassSkeletonBuilder.java:768-814` |
