# 纯 Java Parser 错误/缺失节点产出与补全能力迁移实施计划

日期：2026-10-08。状态：**恢复侧（B0-B9）、纯 Java 补全迁移与旧路径弃用（B10）、补全边界与布局处理（B11）均已实施**（§11–§12）。错配闭合符后的跨层恢复限制仍暂缓（§12.4）。`:` 缺类型问题按用户决策接受。

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

**边界**：类型恢复不改变公开 `TypeRef` 结构与 serde；match `var` 绑定缺名仍丢弃（无节点可承载名字）；
不做增量解析（迁移报告已登记的非目标）或 GDCC 侧消费改动（仅定义合同点，见 §7）。

## 2. 决策记录

### 2.1 本计划直接采用的决策

| 决策 | 内容 | 依据 |
|---|---|---|
| D1 恢复载体 | 复用已有 `ErrorExpression`（`CstIssueKind.ERROR`/`MISSING`）与 `MissingAttributeStep`，**不新增 AST 节点类型、不改 serde schema**（`AstSexprSchema.java:109,122,138` 已注册）、不改 `AstMappingResult`；`AstDiagnostic` 增加可空 `code` 与 `recovery` 组件并保留 4 参构造器（D4/§8.1） | 用户任务；调研 §7.2-7.3 |
| ~~D2 接受面~~ | **已被 D7 取代** | — |
| D3 编辑器范围 | super 前缀保留与补全上下文分类纳入本次交付；signature help DTO 属 GDCC 侧后续能力，本计划只保证 `CALL_ARGUMENT` 分类与 callee 定位 | 用户任务；调研 §8.5-5 |
| D4 诊断结构 | **API 稳定性不作约束**（用户 2026-10-08）：`AstDiagnostic` 直接修改，不兼容由下游承担 | 用户决策；调研 §7.4-7.5 |
| D5 旧路径弃用 | 旧 tree-sitter 补全/解析入口在本计划交付后**标注弃用**（`@Deprecated` + 迁移指引注释），移除另排（§8.3） | 用户决策 2026-10-08 |
| D6 sealed-outcome | §8.2 的损伤传播采用 **`ParseOutcome<T>` 密封接口**。表达式和类型的损伤都随返回值传播；吸收宿主在自身结构完整时返回 `EnclosedDamage`，保留子损伤但不阻断续折或表面形态检查。 | §8.2 |
| D7 `:` 缺类型 | 早期计划曾允许静默省略；现行必选类型槽明确诊断已写 `:` 但缺失类型，合法省略与 `:=` 推断不受影响。 | §2.2 |
| D8 闭合符诊断 | 采纳 2C（§8.4）：缺闭合符产零宽诊断 + code + `RecoveryLink`（owner/facet），不改 AST 形状，不做纯几何归属 | review-expert-c 方案；用户决策 2026-10-14 |
| D9 C3 槽位 marker | 采纳 3A（§8.5）：guard/enum 值/elif 条件/字典 pattern key 的「置 null/丢槽」改为 Phase 1 式槽位 marker；字典 pattern 无载体分支整体 ERROR 回退 | review-expert-c 方案；用户决策 2026-10-14 |
| D10 C2 存活 | assert/const/match 值在槽位失败后**带 marker 存活并消费成功路径尾部**（`)`/`endStatement` 照走），取代「提前 return + 由 synchronize 吃残余」的过渡形态；vendor 对 assert/const 是丢弃，此处为有意优于 vendor 的恢复（登记偏差） | sealed-outcome 使存活消费等价可论证；§8.2.5 |

### 2.2 槽位合同（五态与 MISSING/ERROR 细化规则）

| 源码情形 | 槽位值 | 诊断 | 例子 |
|---|---|---|---|
| 槽位从未开启（合法省略） | `null`（或空列表） | 无 | 裸 `return`、`func f(x)`、`var x` |
| 合法空列表/尾逗号 | 空列表/正常节点 | 无 | `f()`、`f(1,)`、`{}`、`[1,2,]` |
| 已开启但零消费（内容完全缺失） | `ErrorExpression(MISSING, slotName, "", zeroWidth)` | 一条槽位诊断（outcome 为 `Missing` 时由槽位宿主补发，§3.4） | `func f(x=)`、`var x =`+换行、`{"a":}` |
| 已开启且有消费（坏 token 片段） | 可结构化的部分节点（内部含 marker），或 `ErrorExpression(ERROR, "ERROR", fragment, consumedSpan)` | 内层根因诊断（槽位宿主不补发泛化消息，§3.4） | `var hp=1+`（BinaryExpression+MISSING 右槽）、`return 1+`、`@export_range(0,1+,2)` |
| 已写的类型槽位（`TypeRef` 不承载表达式 marker） | 有可用片段时保留 `TypeRef`，否则声明类型为 `null`；`as`/`is` 缺失或失败时保留含表达式 marker 的恢复中缀节点 | `Missing` 由宿主报告，已报错的坏类型不重复补发 | `var x: Foo.`、`func f(x:)`、`x as Array[]` |

机械判定规则（消除主观性）：

- 槽位的形态和根因不靠游标差值或诊断计数推断（§8.2.2）：`Missing` = 零消费且未推诊断；`Failed` =
  已推诊断、无节点（深度预算拒绝可以零消费）；`Damaged` = 可保留的部分节点带外露损伤；
  `EnclosedDamage` = 本层结构完整但含子损伤；`Ok` = 健康值。宿主按变体选择零宽 MISSING、ERROR 片段或原节点。
- **marker 的右端/锚点统一取构造当时的 `extentEnd()`**（最后消费 token 末端，含尾部 lambda 的特例调整，
  `GdScriptParser.java:263-265`），不取 `previous.endByte()`——尾部 lambda 实参场景两者可不同
  （`var x = f(, func(): return 1\n` 中 `previous.endByte()` 会越过 lambda 消费调整点），而父节点 range 均按
  `extentEnd()` 计算，同一基准保证 I3 包络（二审 C-F1）。
- **ERROR span = `[slotStart, extentEnd())`**，即实际消费区间，不含尾随空白；**MISSING = `[extentEnd(), extentEnd())`** 零宽。
- `return` 特判：真正的语句末尾（含 lambda 的外层闭合符与逗号）不开值槽，保留 `value=null`。开启值槽后，
  `Missing` 走有界残片恢复（如 `return =3`），`Failed` 填 ERROR 片段；`Ok`、`EnclosedDamage` 和
  `Damaged` 都保留已建节点。

### 2.3 节点语义纪律（调研 §7.3）

- 不用 `ErrorExpression` 填 `TypeRef` 槽位；不用 `UnknownExpression` 表达语法错误。
- 不把整个受损声明替换为 `ErrorStatement`：健康名字、类型、body、前缀保留在原节点上。
- 正常 `null` 只留给真正 absent 的槽位。
- marker 的 `nodeType`：MISSING 用点分槽位名（词汇表见 §8.2.2），ERROR 片段用 `"ERROR"`，
  对齐 mapper 既有惯例与跨路径诊断可比性。

## 3. 设计不变量（终止性与同步边界护栏）

### 3.1 I1 控制流守恒（sealed-outcome 版）

- marker 构造不消费任何 token（不调用 `advance`/`match`/`consume`）。
- **Pratt 停折**：无值的 `Missing`/`Failed` 停折；`Ok`、`EnclosedDamage` 和 `Damaged` 都可继续折叠，
  但不得绕过结构、预算、优先级、lambda、赋值等原有边界守卫。续折不得擦除左操作数已有的损伤。
- 列表循环由分隔符、闭合符与有界的分隔恢复驱动；marker 本身不移动游标。
- C2 存活位点（assert/const/match 值）失败后消费**成功路径**的完整尾部（比今天提前 return 多消费 `)`/
  `endStatement`），这是有意行为改进，逐点论证见 §5.2。
- 语句分派 D：有值的 outcome 都保留为表达式语句；`finishStatement` 负责语句尾及虚拟 lambda 边界（§8.2.4）。

### 3.2 I2 语句级推进

`parseSuiteImpl` 熔断只统计 null 语句。表达式语句只在 `Missing` 或 `Failed` 无节点可承载时被丢弃；在
lambda 的真实终止边界可置 `lambdaEnded` 而不吃外层 token，其他路径按 EOF 守卫推进并交给同步。
局部递归入口的预算拒绝不自行吃布局 token，交给包围它的构造恢复。熔断继续作为兜底（§8.2.4）。

### 3.3 I3 范围包络

所有节点满足 `parent.startByte <= child.startByte && child.endByte <= parent.endByte`；零宽子节点恰好落在父端点合法。
marker 范围统一以**该侧失败点**的 `extentEnd()` 为基准（§2.2）；字典 entry 范围按
`[min(keyExtentStart, 各组件与 marker 起点), 构造时 extentEnd())` 重算，消除现行 value 侧越界
（调研 §5.5）、key 侧左边界越界（二审 C-F2）与分组 key 括号丢失（三审 C）。

### 3.4 I4 槽位诊断归属（outcome 机械化版）

**槽位宿主只在 outcome 为 `Missing` 时补发本槽位的泛化消息**；`Failed`、`Damaged` 和
`EnclosedDamage` 已有根因诊断。表达式的 `absorbSlot` 与类型宿主按变体分派（§8.2.2）：
`f(g(1+))` 的诊断来自 `1+` 的右槽，外层实参收到 `EnclosedDamage`，不补发。缺闭合符诊断（2C）与
槽位诊断独立。

## 4. 行为需求（按槽位）

> 机制设计见 §8.2/§8.4/§8.5；本节只规定各位点的**目标行为**（实施步骤在 §9）。行号为现行值。

### 4.1 表达式族位点

| # | 位点 | 位置 | 目标行为 |
|---|---|---|---|
| E1 | Pratt 核心 | 2576-2641 | `parseExpression`/`parsePrecedence` 返回 `ParseOutcome<Expression>`；前缀无规则 → `Missing`；深度预算 → `Failed`；只有无值 outcome 或原有边界守卫使循环停折 |
| E2 | 终端前缀 | 2643-2657 | literal/identifier/self → `Ok`；lambda 头部健康 → `Ok`，签名自身或返回类型有词法或结构损伤 → `Damaged`，函数体恢复留在自身槽位 |
| E3 | 一元/二元/`not in`/三元/赋值 | 2943-3085 | 失败子槽由 `absorbSlot` 保留载体；运算符无须抹去 `EnclosedDamage`，未收口的操作数或运算符自己的语法损伤则为 `Damaged`。三元仍按 condition → `else` → false 解析 |
| E4 | cast/`is`/`?` | 3029-3094 | `as`/`is` 保留已消费的 `TypeRef`，坏类型使表达式为 `Damaged`；无类型节点时用 `RecoveredInfixExpression` 保留左操作数及 MISSING/ERROR marker；非法 `?` 也保留带 ERROR marker 的恢复节点 |
| E5 | 分组 | 2662-2671 | 真实 `)` 闭合后继承内层有值 outcome；缺失或错闭合返回 `Damaged`。内层无节点时保留 `Damaged(slotMarker("grouping"))` 并结算本层闭合符 |
| E6 | 数组 | 2675-2695 | 保留坏元素及 `array.element` marker；结构完整的数组返回 `EnclosedDamage`，自身未闭合或列表分隔损坏时返回 `Damaged` |
| E7 | 字典 | 2707-2797 | 键值各有 marker；显式 `:`/`=` 可见证双侧 `Missing` 的条目。结构完整的字典吸收子损伤。Lua 风格键只在 `Ok` 或 `EnclosedDamage` 时检查表面形态，分组中的外露损伤不触发二次形态诊断 |
| E8 | getNode | 2807-2865 | 6 个失败位点 → `Failed`（消费过、诊断已推、无节点）；成功 → `Ok` |
| E9 | preload | 2870-2885 | 路径槽 `absorbSlot("preload.path")`；路径损伤由闭合的 `preload(...)` 吸收；缺 `)` 则本层 `Damaged` |
| E10 | await | 2889-2897 | 操作数槽 `absorbSlot("await.operand")` 并传播该操作数的损伤（同 E3） |
| E11 | super | 2907-2939 | 裸 `super`、`super.` 缺名、`super.name` 缺 `(` 均保留部分节点并返回 `Damaged`；完整调用边界吸收实参损伤 |
| E12 | 后缀链 | 3121-3208 | 缺名点号追加零宽 `MissingAttributeStep`，标记本链 `Damaged`；缺名末步阻止后续 `(`/`[` 折向前一个属性。实参/索引损伤由完整调用/下标吸收；在结构、优先级、预算与 lambda 守卫允许时继续折叠 |
| E13 | 实参列表 | 3227-3247 | `parseCallArguments` 始终带列表；坏实参用 `call.argument` marker 或部分节点保留。实际闭合且分隔完整时吸收子损伤，否则为 `Damaged` |
| E14 | 下标索引 | 3165-3189 | 索引槽 `absorbSlot("subscript.index")`；当实际 `]` 闭合且链自身完整时吸收索引损伤，缺失或错闭合则为 `Damaged` |

### 4.2 语句/声明槽位

| # | 槽位 | 位置 | 目标行为 |
|---|---|---|---|
| S1 | return 值 | 1227-1243 | 真正语句结束时可省略值；开启的槽按 §2.2 恢复并绑定 `return.value` |
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
| S16 | 语句分派 D | 1281-1302 | 见 §8.2.4（无值丢弃；有值的三态均保留） |

### 4.3 显式不改

- 公开 `TypeRef` 与 serde 合同；signal 参数缺名 `break`、signal 拒默认值检查
  （2180-2182 在 S3 marker 化后会把 `signal s(x = )` 识别为有默认值而报错——可接受的更严格识别，登记 §6.2）。
- match 缺 INDENT 的 body 归属（头部换行已消费时 `synchronize` 立即停，body 归外层 suite，不变）。
- `frontend.cst`/`frontend.lowering` 与 `CstToAstMapperErrorRecoveryTest`（旧路径，不在本计划）。

### 4.4 补全上下文能力迁移（Phase 3）设计

1. `frontend.gdscript.CompletionContext` 保留三个 record 组件：`kind`、`replaceableRange`、`receiverRange`。
   `Kind` 有八值：`MEMBER_ACCESS`、`IDENTIFIER`、`TYPE_POSITION`、`CALL_ARGUMENT`、`PARAMETER_NAME`、
   `SUITE_START`、`EXPECTED_KEYWORD`、`UNKNOWN`。`MEMBER_ACCESS` 必带 `receiverRange`，其他值不带。
   弃用的 `infra.treesitter.CompletionContext` 保留原五值、字段与运行时行为；GDCC 当前仍从该旧入口消费。
2. 包私有 `CompletionContextAnalyzer` 输入托管 token 视图、容错 AST 和 UTF-8 字节偏移。
   一般 `ERROR` token 不参与分类；未闭合字符串范围单独保留。托管模式保留真实的错配闭合符。
   输出是按以下边界和优先级命中的分类：
   - 预备：offset clamp 到 `[0, sourceLength]`；`nodeAt(root, offset)` 按 range 包含下钻（`start <= offset <= end`，
     零宽子节点仅在 offset 恰好等于其位置时命中），得到节点路径；同时取 offset 所在/之前的 token。
     **token 流契约**：独立 facade `tokenize` 用自己的括号验证与布局处理，补全不依赖该入口。
     补全视图按**有效 multiline 上下文**处理托管 tokenizer 的布局：
     - 以一个小栈复现 parser 语义：表达式分组、调用、下标与签名参数表的 `(`/`[`/`{` 压入启用帧；
       **类型容器的 `[`/`]` 只跟踪配平、继承当前 multiline 状态，不自行启用**（parser 的 `parseTypeInternal`
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
     - 闭合符优先交给最近的同类开括号，并截断其中的未闭帧；没有同类帧时只替换最内层的异类开括号。
       只有精确同类配对能作为合法 primary/postfix 包裹。错配、截断和未闭硬边界单独记有效终点。
       补全与 parser 共用惰性括号扫描器，以阻止未闭表达式吞入后续硬边界；活跃的函数体保持自己的布局。
   - **receiver 通用公式**（R1/R2/R3/R3b 共用）：receiver 末端 = **点号前最后一个非布局 token 的末端**（token 级定位，
     不是 `base.range().endByte()`——分组 receiver `(node).pa|` 的 base 被解包为内层节点，直接取 base.end
     会漏掉 `)`）；receiver 起点 = 链 extentStart（AST 存活时），AST 被丢时用**括号配平的
     primary/postfix 反向扫描**：从点号反向跳过配平的 `)`/`]` 组、名字、`$`/`%` get-node 前缀
     与调用/下标尾巴，覆盖 `(node)`、`$Child`、`items[0]`、`make()` 等 receiver，并遵守有效布局硬边界
     （对齐旧分析器 `receiverRangeBeforeDot` 接受 named 节点的口径）。
   - R1 链上名字：offset 落在某 step 的名字 token 内（名字 token = 位于 `step.range().startByte()` 处的 token；
     对 `AttributeCallStep`/`AttributeSubscriptStep` 这样取避免误覆盖实参区）→ `MEMBER_ACCESS`，
     `replaceableRange` = 该名字 token range，`receiverRange` 按通用公式。
   - R2 点号光标：光标已越过 `.` 且属于某 `AttributeExpression` 链 → `MEMBER_ACCESS`，`replaceableRange` =
     点号后名字 token（无则点号末端零宽），receiver 按通用公式。
     共享边界按用户确认采用左侧名字优先：`node|.pa` / `node|.` 为 IDENTIFIER(`node`)，
     `a.b|.c` 为 MEMBER_ACCESS(`b`, receiver=`a`)，`Outer|.Inner` 为 TYPE_POSITION(`Outer`)；
     右侧点号不得清空已经命中的词尾名字。
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
     仅补全可以在未闭 `[` 后识别紧邻的更深缩进类型片段（允许整行注释作 trivia）；光标前缩进必须超过声明行，
     且该行出现独立语句、声明、运算符或开头的祖先分隔符便不采用该推断。该规则不重开 multiline，
     也不改变严格解析对未闭类型的拒绝。
   - R6 零宽类型槽（token 回扫）：offset 前一非布局 token 是**算符角色**的 `as`/`is` → 零宽 `TYPE_POSITION`——
     `as`/`is` 属于 `isNodeName`，点号后是合法成员名（`var x = obj.as |`），get-node 路径名同理（`$as`、
     `%is`、`$/as`）；角色判定（前一非布局 token 不是 `.`、`/`、`$` 或 `%`）与 R5 的类型上下文回扫共用，
     成员名/路径名角色的 `as`/`is`
     不开启类型槽。类型测试开启符是算符角色的 `as`，或算符角色的 `is`（可紧跟一个 `not`，即
     `is not`——可越过这个 `not` 再穿过点分名与容器后缀；`x not |` 与 `obj.not |` 不开启类型槽）。是 `extends` 且
     目标尚未开始（无名字 token 或光标在其前）→ 零宽 `TYPE_POSITION`；是函数/lambda 签名中的 `->` 且
     返回类型尚未开始 → 零宽 `TYPE_POSITION`；是 `:` 则仅在变量/常量/参数/signal 上下文（回扫序列形如
     `var|const|for name :` 或签名参数表内 `name :`）→ 零宽 `TYPE_POSITION`；**排除**函数体冒号（`:` 前为 `)`）
     与字典冒号。
     只有名字 token 自身属于类型槽时才替换它；`: |in`、`is |and`、`Array[int]|in` 不覆盖右侧关键字，
     进入非类型关键字内部（`i|n`）后不再以之前的类型开启符判为类型槽。`is` 后可选的 `not` 仅对首个非布局 token 有效。
   - **光标空槽**：光标没有命中名字、类型、成员或 AST 标识符时，在调用实参回退之前检查前一个 token
     是否属于尚未退出的槽。字典值和声明/参数的 `:=`、`: =` 初始值为 `IDENTIFIER`；
     合法签名的 `(`、顶层逗号和参数名前的 `...` 为 `PARAMETER_NAME`；同逻辑行的函数/匿名函数体入口
     为 `SUITE_START`；表达式操作数后的中缀 `not` 为 `EXPECTED_KEYWORD`，仅表示预期 `in`。
     每个槽按所属结构、入口和退出点识别，返回光标处 UTF-8 零宽范围及空 receiver。
     非法换行签名仍非调用；`func f():\n\t|` 已越过入口；一元 `not`、`is not` 和路径中的 `not` 不走中缀槽。
   - R7 调用实参：token 级找最内层有效作用域含 offset 的括号（可保持未闭至 EOF），先做**括号角色判定**：
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
   - R8 兜底：字符串/注释内部、空白/空源/其他 → `UNKNOWN`。光标在 `#` 或字符串 token 首字节之前不算内部：
     `node.pa|# comment` 保持成员补全；按用户确认，`target(|"hello")` 与未闭合变体为空实参 CALL_ARGUMENT。
     前缀字符串首字节之前同理，但进入 `r`/`&`/`^` 前缀之后即为字符串内部；未闭合字符串末端仍 UNKNOWN。
   - 优先级冲突显式规则：缺名点号位（R3/R3b）压过实参区（R7）；光标深入受损 call 的实参区（越过空首实参位）
     才让 R7 生效。
3. `GdScriptParserFacade.parseCompletionContext(String source, long byteOffset)` 结合容错 AST 和独立的托管 token
   视图；无状态、线程安全契约与既有入口一致。
4. 新增 `GdCompletionContextTest`（`frontend/gdscript` 测试包）：对旧 `CompletionContextTest` 的 21 个用例**逐条编号
   映射**（不许只写「镜像语义」），另加恢复驱动用例：`super.|`、`super.ba|`、`self.b.(|`、EOF 处
   `target(|`/`target(1,|`/`target(1|`、CJK/emoji/CRLF 坐标、无最终换行。
5. 旧路径弃用标注（D5，§8.3）在 Phase 3 交付后进行：为 `infra.treesitter` 的公开入口类加 `@Deprecated` 与
   迁移指引注释。

## 5. 终止性与同步边界风险分析

### 5.1 表达式失败传播路径（sealed-outcome 版）

失败以 `ParseOutcome` 变体沿 `parseSuffixChain`/`parsePrecedence` 传播：仅无值结果不参与后续折叠，
有值结果按原有结构与优先级守卫续折并保留损伤；语句分派见 §8.2.4。传播父节点保持三元、二元、赋值的
子槽消费顺序；后缀链先结算 multiline 和闭合符再建节点；列表由有界分隔恢复和闭合 token 推进。
`if f(,): pass`、`assert(f(,))`、`preload(f(,))` 等嵌套失败：内层 `Missing` 填 marker、宿主存活，
`synchronize` 不再介入这些位点（无 panic 尾差）——`panicMode` 仍由诊断触发，语句尾 `synchronize`
（1311-1312）照常，但当前 token 已是行尾/同步点，零消费退出。

### 5.2 存活变化的逐路径论证（取代原 super 门控分析）

- **D 语句分派**：有值结果（含 `Damaged`）保留为语句，由 `finishStatement` 处理同行垃圾和真实结束符。
  lambda 的外层逗号或闭合符属于外层构造，不能因保留部分节点而被内层同步吃掉。
- **C1（if/while/for）**：失败路径今天已消费整链（suite 照走），存活只改返回值，token 流不变。
- **C2（assert/const/match 值）**：失败路径今天少消费（assert 不吃 `)` 不调 `endStatement`、const 不调
  `endStatement`）。存活后按**成功路径**消费：对良形尾部（`assert(1+, "msg")`、`const A = 1+`、`match 1+:`）
  消费完全一致。但须登记**同步集早停差异**：今天的 `synchronize` 会在同步关键字（如 `FUNC`）前停下，存活
  路径则把同步关键字当作槽位内容继续解析——`assert(1+, func(): pass)` 今天在 `func` 前停、之后可能按独立
  func 语句解析，存活后 `func(): pass` 被解析为 assert 的消息槽。这是对消费与归属的**有意改变**（消息槽
  拿到结构、减少裸 sync 丢弃），同样情形还有跨行消息与连续结束符；垃圾尾部（`assert(1+ @`）存活后
  closer 诊断 + `endStatement` 报错 + `synchronize` 吃残余，比今天多一条 `endStatement` 诊断（D10 接受）。
- **super 三分支**（E11）：受损前缀仍带节点，与其他有值 outcome 共用续折和语句分派规则。`return super.`、
  `var x = super.` 和 `if super.:` 保留前缀及其原发诊断。

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
| T1 | `GdScriptParserErrorRecoveryTest` 新增 | `return 1+` 保留二元表达式与右槽 MISSING，只有原发诊断；裸 `return`、`return 1`、`call(func(): return, 1)` 为健康对照；`return =3` 保留 ERROR 片段 |
| T2 | 同上 | `var hp = 1+` → value 为带 MISSING 右槽的 BinaryExpression；`var hp =`+换行 → MISSING 零宽于 `=` 末端；`var hp = $/!!`（getNode `Failed`）→ ERROR 片段；健康 `var mp = 2` 兄弟不变 |
| T3 | 同上 | `func f(x: int = 1+)` → 名/类型保留 + 受损默认值；`func f(x=)` → MISSING 零宽；`func f(x:=1)`、`func f(x)` 零诊断 |
| T4 | 同上 | `@export_range(0, 1+, 2)` → 实参 `[0, BinaryExpression(1,MISSING), 2]` 三位置保留；`@export_enum("A", ,"B")` → 中位 MISSING；合法注解零诊断 |
| T5 | 同上 | `[1,,3]` → 中位 MISSING；`{"a":}` → value MISSING 锚在 `:` 末端；`{:1}` → key MISSING；`{1+:2}` → 受损 BinaryExpression；`{(a): 1}` 的 entry 含 `(`；`{:}` 保留带两个 MISSING marker 的条目；空容器和尾逗号不制造伪条目 |
| T6 | 同上（更新+新增） | 2C：`var hp = (1` → value 健康 Literal(1) + 缺 `)` 零宽诊断带 code `parse.missing_closer.grouping` 且 `recovery` 绑定该 VariableDeclaration/facet `variable.initializer`；`var cb = (func(): return 1` 绑定外层变量、`var cb = func(): return (1` 绑定内层 return（归属边界）；`target(1` EOF → CallExpression 存活 + 缺 `)` 诊断（现状锁定）；诊断不跨 serde 持久化的限制写入文档（§7 第 8 条） |
| T7 | 新增范围不变量测试 | 恢复语料 + 合法 fixture 全 AST 递归断言 I3 包络（零宽等于父端点合法），含尾随空白/EOF/垃圾前缀用例与 `var x = f(, func(): return 1\n` 的 `extentEnd()` 特例；不许新增豁免 |
| T8 | `AstSexprSerdeTest` 新增 | 含 ERROR/MISSING marker 的 AST serde 往返相等 |
| T9 | `GdScriptParserTest` 更新+新增 | `self.b.(1)` → `CallExpression(callee=AttributeExpression(self,[b,Missing]))` 不折进 `b`；`self.p.[0]` 同理；`a.b(1)`/`a.b[0]`/`a. b`（空白合法）健康链形状不变；`a..(1)` 仍按单 token `..` 报错不误判 |
| T10 | 同上 | `node.` EOF 保留零宽缺名 step；`super.`/`super.ba` 保留前缀。受损语句可续折，同行尾垃圾交 `finishStatement`，lambda 外层分隔符仍交给其所有者 |
| T11 | 类型槽位回归测试 | 已写但空的必选类型槽补发上下文诊断；非法非空类型保留可用片段或报告自己的根因，无重复缺类型诊断 |
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
| T20 | 同上 | `foo(1+)` 保留后续兄弟；`a[1+]+2`、`(1+)+2`、`foo([1+]).bar`、`foo(1+).bar` 和 `a[1+][2]` 保留续折；`foo(1+)(2)` 还报告外层非法直接调用。lambda 边界、空分组、嵌入式非法赋值节点及深度预算均检查原发诊断与节点范围 |
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
| A（宿主存活槽位） | 变量/参数默认/注解/数组/字典列表槽 | 宿主存活、槽位 null | `absorbSlot` 保留节点或填 marker；表达式吸收宿主报告 `EnclosedDamage`，return 例外见 S1 |
| B（表达式内部传播点） | 分组/实参/下标/操作数/await/preload/super | null 上浮 = 停折信号 | 有值结果保留损伤并可续折；无值结果停折（§8.2.3） |
| C1（失败后仍消费整链） | if/while/for 头部 | 吃完 suite 返回 null | 带 marker 存活（S5） |
| C2（失败提前返回） | assert/const/match 值/match pattern | 提前 return null | 带 marker 存活 + 消费成功路径尾部（S6-S9，D10） |
| C3（宿主存活仅丢槽） | guard/enum 值/elif/字典 pattern key | 置 null/丢槽 | 槽位 marker（S10/S11/S13/S14，3A） |
| D（语句级分派） | 表达式语句 1281-1302 | null → drop+熔断 | 按 outcome 门控（§8.2.4） |

lambda 头部健康时返回 `Ok`；签名自身或返回类型有词法或结构损伤时，保留节点并返回 `Damaged`。
返回类型诊断仍由其槽位持有，函数体的恢复不改变前缀结果。`as`/`is` 的类型失败和非法 `?`
保留左操作数与原发诊断，返回 `Damaged`，不降级为健康左操作数。

#### 8.2.2 `ParseOutcome<T>` 类型与机械化规则

包私有密封接口 `frontend/gdscript/ParseOutcome.java`：

```java
sealed interface ParseOutcome<T> {
    record Ok<T>(T value) implements ParseOutcome<T> {}        // 健康节点
    record EnclosedDamage<T>(T value) implements ParseOutcome<T> {} // 结构完整，子树受损
    record Missing<T>() implements ParseOutcome<T> {}          // 零消费失败，未推诊断
    record Failed<T>() implements ParseOutcome<T> {}           // 已推诊断，无节点（通常有消费；深度预算拒绝时为零消费）
    record Damaged<T>(T value) implements ParseOutcome<T> {}   // 部分节点，已推诊断
}
```

- **诊断携带合同**：`Missing` 由槽位宿主补发上下文消息（I4）；`Failed`、`Damaged` 与
  `EnclosedDamage` 的根因已在内部报告。`valueOrThrow()` 只供一定携带值的路径使用（如实参列表）。
- **`slotMarker(outcome, slotStartByte, slotName)`**：`Missing` → `ErrorExpression(MISSING, slotName, "",
  [extentEnd(), extentEnd()))`；`Failed` → `ErrorExpression(ERROR, "ERROR", slice, [min(slotStart, end), end))`
  其中 `end = extentEnd()`——`Failed` 允许零消费（深度预算拒绝发生在任何消费之前，`extentEnd()` 可以落在
  `slotStart` 之前），此时退化为 `[extentEnd(), extentEnd())` 零宽片段，不构造倒置区间（`SourceTextIndex`
  拒绝 `start > end`）。不触碰游标。
- **`absorbSlot(outcome, slotStartByte, slotName, contextMessage)`**：`Ok`、`EnclosedDamage`、`Damaged`
  直接保留节点；`Missing` 补发上下文消息并建零宽 marker，`Failed` 保留 ERROR 片段。已有归属的词法片段优先保留。
  `return` 用自己的值槽规则（S1）；调用/下标槽用 `absorbSlot` 保留值，同时用原 outcome 计算宿主损伤。
- **类型槽**：内部 `ParseOutcome<ParsedType>` 同时保留 `TypeRef` 与容器标识，公开结构不变。`Missing` 由调用方
  补发相应 `MISSING_TYPE_*`；`Failed`（空泛型实参、非法 `void`、预算拒绝等）不补发。带点号的残片与未闭合泛型
  返回 `Damaged`，已闭合但含非法内层容器或坏内层类型时返回 `EnclosedDamage`。实际消费的类型 token 或闭合符
  上结算的词法错误也保留为损伤。`for` 类型非 `Ok` 时仅在物理同行跳过坏 token，可匹配同一逻辑头已可见的 `in`，
  包括显式续行；保留祖先分隔符、真正的套件冒号和 lambda 边界。
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

- **Pratt 停折**：`Missing`/`Failed` 无值，直接传播。其他三态都可在已有优先级、结构、预算、lambda 与
  赋值守卫下续折；左侧已有的 `Damaged` 不因新运算符而变成 `EnclosedDamage` 或 `Ok`。
- **传播父节点**：未定界的操作数保持已有损伤；运算符自身缺项（如 `not in` 缺 `in`、三元缺 `else`）
  或操作数仍不完整，返回 `Damaged`。禁止嵌入的赋值仍建 `AssignmentExpression`，保留左右子树并返回
  `Damaged`；右槽缺失另用 `assignment.value` marker。对 `as`/`is`，坏类型使其表达式为 `Damaged`，
  有类型片段保留 `TypeRef`，无片段用 `RecoveredInfixExpression`。
- **分组**：由自己结算 `)`，无自身损伤且实际闭合时透明地继承内层的有值 outcome；括号不引入新 AST 宿主。
  因此 `(1+)` 仍为 `Damaged`，`([1+])` 继承 `EnclosedDamage`；坏闭合符、内层无值，或分组入口或闭合符
  上的词法错误均使分组返回 `Damaged`。
- **吸收宿主**：数组、字典、调用和下标将失败子槽保留为 marker 或部分节点。自身结构完整且实际闭合时
  返回 `EnclosedDamage`，而不是 `Ok`；真实闭合符或分隔符上结算的子槽尾部词法错误同样保留。坏闭合、缺分隔符
  或混用字典分隔风格是本层结构错误，返回 `Damaged`。Lua 字典键仅检查
  具有完整外形的 `Ok`/`EnclosedDamage`，因此 `[1+]` 和 `foo(1+)` 作为 Lua 键仍非法，`(1+)` 不二次报错。
- **后缀链**：缺名点号以零宽 `MissingAttributeStep` 保留并标记外露损伤；后续 `(`/`[` 不折向前一个属性
  step，但链可在守卫允许时继续。闭合的调用/下标吸收子损伤，如 `foo([1+]).bar` 和 `foo(1+).bar` 都续折。
  调用与下标的范围右端包含实际消费和子节点末端，`finishChain` 取最后一个 step 的末端。
- **super**（E11 三分支存活）：receiver 与已知名前缀保留在 AST 中供补全锚定。
- **getNode**：路径形状失败 → `Failed`（片段由宿主槽位落成 ERROR）。

#### 8.2.4 语句分派（D）与熔断口径

`parseStatement` 的表达式分支按 outcome 分派：

- `Missing`：无法建立表达式语句；非 lambda 终止边界时推进并报告上下文错误，null 计入熔断。
- `Failed`：无法建立表达式语句；原发诊断已存在，不再补发。遇 lambda 虚拟边界时不消费，否则以 EOF 为守卫
  推进并交给同步；null 计入熔断。
- `Ok`、`EnclosedDamage`、`Damaged`：统一保留 `ExpressionStatement` 并绑定 owner，由 `finishStatement`
  结算语句结束或报告残余尾部；非空节点不计入熔断。
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

- **位点与 code**：分组、数组、字典、调用实参、下标、注解实参、preload、assert 和类型括号等使用各自的
  `parse.missing_closer.*` / `parse.mismatched_closer.*` code。
- **形状**：缺闭合在 `[extentEnd(), extentEnd())` 报零宽诊断；错闭合诊断覆盖真实 token。对返回 outcome 的
  表达式与类型，这类诊断表示当前层结构损伤，不等同于已闭合的子槽损伤。
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
  - `as`/`is` 中类型括号沿用当前表达式槽位归属；若没有外层 recovery frame，则在操作符处开启
    `type.argument` 并绑定到实际保留的中缀节点。
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
| B1 | Pratt 核心：`PrefixRule`/`InfixRule` 签名、`parseExpression`/`parsePrecedence` outcome 化、循环头停折、终端前缀 `Ok` 与 lambda 前缀按头部结果返回 `Ok` 或 `Damaged` | `GdScriptParser.java` | 同编译单元，不单独编译 |
| B2 | 传播父节点与前缀：分组/数组/字典（删 `nullPlaceholder`）/getNode/preload/await/一元/二元/`not in`/三元/cast/`is`/`?`/赋值（E3-E10、E14 的索引槽） | 同上 | 同上 |
| B3 | 后缀链与 super：E11/E12/E13（MissingAttributeStep 障碍、`extentEnd()` 右端、实参列表 outcome） | 同上 | 同上 |
| B4 | 吸收语句槽 + 参数顺序：S1-S4、S14（enum 值）、S12（参数顺序，与 S3 同批耦合） | 同上 | 同上 |
| B5 | C1/C2/C3：S5-S11、S13 | 同上 | 同上 |
| B6 | 语句分派 D（S16）+ 熔断口径核对 + `parse()` 返回前的 RecoveryLink 可达性清扫（§8.4） | 同上 | `./gradlew classes` + `script/run-gradle-targeted-tests.sh --tests GdScriptParserErrorRecoveryTest,GdScriptParserTest,GdScriptParserFacadeTest` 初跑（预期 §6.2 登记项失败） |
| B7 | 恢复侧测试：§6.2 逐条改写 + T1-T14、T18-T21 新增 | `src/test/...` | targeted 全绿 |
| B8 | 文档：`godot-java-parser-implementation.md` 词汇表（code/marker/facet）与产出政策、`architecture-overview.md:190` 政策行、本计划状态收尾 | `doc/` | 文档与实现一致性自查 |
| B9 | 恢复侧评审：review-expert-a + review-expert-c 并行审阅实现与文档，修复中/高/阻断项后复审 | — | 双 APPROVE |
| B10 | 补全迁移（§4.4）：`CompletionContext` DTO、`CompletionContextAnalyzer`（R1-R8 + token 级回退 + lambda 禁用帧）、`GdScriptParserFacade.parseCompletionContext`、T15-T17；随后执行旧路径弃用（§8.3） | `frontend/gdscript/CompletionContext.java`（新）、`CompletionContextAnalyzer.java`（新）、`GdScriptParserFacade.java`、`infra/treesitter/`（弃用标注） | `GdCompletionContextTest` 全绿 + parity 回归 |
| B11 | 补全边界与布局处理（§12，已实施；跨层限制见 §12.4）：完整路径、共享光标/类型槽、签名与无体函数帧、lambda 逻辑布局 | `CompletionContextAnalyzer.java`、`CompletionTokenStream.java`、`GdCompletionContextTest.java`；关联文档已同步 | 18 类 959 项、独立进程及无 native 验证通过；独立复核通过 |

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

## 11. 纯 Java 补全迁移结果

- `frontend.gdscript.CompletionContext`、`CompletionContextAnalyzer` 与包内 `CompletionTokenStream` 提供纯 Java 补全上下文分类，公开入口为无状态的 `GdScriptParserFacade.parseCompletionContext(String, long)`。入口不初始化 tree-sitter/native，可并发共享 facade。
- 分类返回 `MEMBER_ACCESS`、`IDENTIFIER`、`TYPE_POSITION`、`CALL_ARGUMENT`、`PARAMETER_NAME`、`SUITE_START`、`EXPECTED_KEYWORD` 或 `UNKNOWN`。范围为 UTF-8 字节偏移与零基行/字节列，替换范围覆盖完整名字或光标空槽，仅成员访问携带 receiver。
- 容错 AST 提供幸存节点的结构与范围；独立 token 视图覆盖恢复中被丢弃的表达式，维护括号角色、类型位置、光标空槽和有效布局。一般 `ERROR` 不进入分类流，未闭合字符串范围单独保留，真实错闭合符经托管通道传递。
- `infra.treesitter` 的 facade、补全 DTO/分析器与 snapshot 已标注 `@Deprecated` 并提供迁移入口，运行时行为不变。CST/lowering 与旧后端测试继续保留作为兼容/parity 对照。
- `GdCompletionContextTest` 保留与旧补全 21 例的逐条对应，并覆盖类型/实参回退、受损链、Unicode/CRLF、字符串/注释、截断与并发。EOF 下 `target(1` 返回 CALL_ARGUMENT，是相对旧 CST 分类器 UNKNOWN 的有意改进。
- 当前能力不包含候选生成、语义解析、注解名补全、signature-help DTO 或增量解析。GDCC 尚未升级到纯 Java 入口；升级时若要维持旧五值 API，新三值须投影为 `UNKNOWN` 且不提供候选，其余五值按原名和原行为保留。

## 12. 补全边界与布局处理结果

### 12.1 实现范围

边界与布局处理位于 `CompletionContextAnalyzer.java` 和 `CompletionTokenStream.java`，测试位于 `GdCompletionContextTest.java`。纯 Java facade 签名与三个 record 组件不变，纯 Java `Kind` 扩为八值；严格 parser 和已弃用的五值 tree-sitter 入口延续原合同。

路径、函数签名、lambda 和类型语法以 vendored Godot 的 `parse_get_node`、`parse_function_signature`、`parse_lambda`、`parse_type` 与 `parse_match_branch` 为参照；语法分类不等同于 Godot 的语义补全 DTO。

### 12.2 已实现行为

- **完整 receiver**：get-node 路径支持名字/普通字符串段、`/%`、绝对路径和独立 `%Child`，例如 `$Root/"Child"`、`$Root/%Child`、`$/Root/"Child"/%Leaf`。AST 存活或目标链被丢弃时，回退均保留完整路径；分组、调用与下标的 receiver 保留各自完整范围。
- **路径与算符分离**：`%` 依据完整 primary/postfix 的前缀角色区分唯一路径与取模，`await`、`return` 等关键字只有在已确认的路径/成员角色下才作为名字。普通除法、取模的左操作数不进入右侧 receiver；`a / %Child.method` 的 receiver 为 `%Child`，`a % (b).method` 为 `(b)`，`value%-name.method` 为 `name`，`(-name).method` 为 `(-name)`。回扫不跨有效硬布局边界。
- **光标共享边界**：名字与点号相接时左侧名字优先，`node|.pa` 返回 IDENTIFIER(`node`)，`a.b|.c` 替换 `b`，`Outer|.Inner` 替换类型名 `Outer`。注释或字符串起点之前仍属代码侧，`target(|"s")` 为 CALL_ARGUMENT；进入引号、字符串前缀或注释后为 UNKNOWN。闭合字符串不包含末端，未闭合字符串和注释包含末端。
- **类型结束边界**：有限类型状态区分起点、`is` 的一次可选 `not`、首段、点分段、容器完成与字符串 `extends` 路径。类型替换仅覆盖自身处于类型位置的名字，`for x: Array[int]|in arr:` 不覆盖 `in`，`x is Foo not bar|` 为 IDENTIFIER，`x is not Foo|` 为 TYPE_POSITION。完整 cast 后的 `when` 不延续类型；容器类型完成后的 `.size` 与 `[idx]` 属于表达式成员/下标，点分类型后的 `[` 亦为表达式下标。`extends "res://base.gd".In|ner` 即使没有 TypeRef AST，仍为 TYPE_POSITION，字符串内部仍 UNKNOWN。
- **函数头与无体签名**：待开启参数表、RETURN_TYPE、INLINE 与 INDENTED body 各自维护生命周期。函数名接受上下文标识符，包括 `match`、`when`、`PI`、`TAU`、`INF`、`NAN`。无效头或无体签名退出后，当前 token 按外层状态重新处理；`func\ntarget(|` 为 CALL_ARGUMENT。合法的参数表内部换行与反斜杠续行保留 `PARAMETER_NAME`，但参数表之前的硬换行禁止该槽，且不会把签名误认作调用。受损签名的默认值内真调用仍可返回 `CALL_ARGUMENT`。
- **逻辑布局与缩进恢复**：有效硬换行而非物理行号决定逻辑行和 body 边界；显式续行不覆盖外层缩进基线，也不提前结束 INLINE body。`a.` 后的独立下一行 `b` 为 IDENTIFIER，真实反斜杠续行后的 `b` 为 MEMBER_ACCESS。函数帧退出按 LIFO 恢复外层缩进；退出 INLINE/RETURN_TYPE 时先保留终止它的硬换行，再恢复外层 multiline。
- **match 模式逗号**：INDENTED 函数帧局部跟踪 match 值、待分支、模式头、body 及其缩进。模式头中的逗号不结束 lambda；分支冒号或模式头硬换行结束该模式头。分支 body 的尾逗号在存在外层表达式括号时仍可结束 lambda 并恢复外层调用。多模式、数组模式、多分支与嵌套 match 均有回归覆盖。
- **受损类型帧退出**：硬换行或普通 token 使类型失效时，连续非 multiline 类型容器先退出，再处理 RETURN_TYPE 与当前 token；硬换行不会被恢复后的外层 multiline 吞掉。签名参数中合法继承 multiline 的类型容器不受该清理影响。`var x = g(func() -> Array[\n,\n\tnode.\n\tpa|)` 和同一行 `Array[, node.\n\tpa|` 均恢复 MEMBER_ACCESS(`pa`)，receiver 为 `node`；后者目标链不在最终 AST 中，由 token 回退提供结果。
- **可信类型续写和光标空槽**：`var x: Array[\n\tIn|ner` 在光标行是合格的类型片段时返回 `TYPE_POSITION`，同级输入、声明与独立语句保持原分类。`{"a":|}` 和 `var x :=|` 返回 `IDENTIFIER`，`func f(|): pass` 返回 `PARAMETER_NAME`，`func f():|` 返回 `SUITE_START`，`x not |` 返回 `EXPECTED_KEYWORD`。这些槽位不需要 AST 存活，返回光标处 UTF-8 零宽范围与空 receiver。

上述示例中的 `|` 表示光标，`\n` 表示真实换行。回归测试同时检查 Kind、完整替换范围、receiver/必空 receiver 与 UTF-8 points，并区分健康 AST、损坏但幸存的节点和已丢弃目标链，不假设损坏输入零诊断。

### 12.3 验证结论

- `GdCompletionContextTest` 分开验证健康输入的零诊断和受损输入的诊断与幸存节点，并用无 AST 的视图验证类型续写、空槽与错闭合回退。
- 长尾、重复错闭合、深层嵌套和逐截断测试覆盖每流至多一次括号扫描、UTF-8 范围及 receiver 不变量。本仓库测试核对枚举、record 约束，并演示旧五值的保守投影；不验证 GDCC 的候选路由或范围传递。实际验证需待 GDCC 升级并联调。
- 全量 `clean build` 通过 25 类 1886 项，其中 `GdCompletionContextTest` 1045 项；无失败、错误或跳过。

### 12.4 错配闭合符后的恢复边界

```gdscript
func f():
    var bad = [1)
    a.
    b|
```

此处 `|` 表示光标。补全 token 视图通过托管入口收到真实的 `)`，结束 `[` 的多行作用域而不把两者登记为精确配对。
`a.` 与下一行 `b` 之间的硬换行因此保留，`b` 为 `IDENTIFIER`。同理，`[target(1] |` 不再泄漏实参槽，
`target(1\nfunc g():\n\t|` 在新声明处结束未闭调用；但 `(a.\n\tb|)` 和 `target(1,|` 分别保留成员与实参上下文。
硬结构停止或真实反缩进在逃逸行的行首排除光标；精确或替换闭合符仍允许光标位于闭合符之前。
即使外层保持多行，已退出的子帧也不允许成员或其空槽跨过硬边界。反缩进使用 tokenizer 的逻辑行起点，区分上一行末尾续写与反斜杠单独起行。
独立 token 视图不重建完整表达式树。例如没有 AST 时，容器 cast 接收者 `x as Array[int]` 的成员回扫可能只保留 `[int]`；
公开 facade 有幸存的 AST 链时会使用完整接收者。
