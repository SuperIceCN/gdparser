# gdparser 0.7 纯 Java Parser 错误恢复调研与解决方案

日期：2026-10-07。状态：调研与建议，**尚未实施或接受为新合同**。

本文基于当前 gdparser 文档、实现、定向测试、独立最小复现及固定 Godot vendor 源码，核对同级 GDCC 仓库的
`doc/analysis/gdparser_0_7_pure_java_parser_regressions_report.md`。
没有修改生产源码、现有测试期望、配置或 GDCC 文件；没有把下述方案写进现行事实源。

## 1. 结论

问题的核心不是“必须把每个错误都变成 Error 节点”，而是：

> 当前输出能表示 AST 存活与全局诊断，却不能可靠表示一个具体构造、槽位或后缀是否仍具有完整、可信的语义。

因此，普通节点在错误后存活本身是合理恢复；**把受损构造无标记地变成另一种正常源码的语义形状**才是本轮重点。

| 优先级 | 已确认现象 | 分类与建议 |
|---|---|---|
| P0 策略审计 | 参数冒号后无类型、空默认值、`var x:;` 零诊断且 strict 成功 | vendor 同构路径；不能只靠 ERROR 门禁，需明确接受策略并补充缺失意图 |
| P0 | 损坏默认值变必填、非法参数顺序丢参数 | 部分继承 vendor；保留参数身份，分离签名完整性与 body binding |
| P0 | 坏注解实参压缩后仍像有效注解，坏初始化器仍像正常属性 | Java 丢掉注解语法 invalid 状态；其他部分继承 vendor；在 skeleton 消费前发布损坏信息 |
| P1 | 字典缺 value 的普通 null 占位超出 `DictEntry` 范围 | Java 范围适配缺陷，可独立修复，不应添加审计豁免 |
| P1 | 缺名点号后的调用/下标重绑定到前一个有效属性 | Java 后缀折叠缺陷；缺失步骤必须构成 folding barrier |
| P1 | 坏带值 return 退化成裸 return，新增检查与 sema 双报 | 恢复身份及诊断归属不足；按根因/构造关联，不按消息或范围相等去重 |
| 迁移能力 | `super.`、`super.ba` 没有 receiver，未完成调用信息不稳定 | 补全 hook 为已裁剪能力；不是合法语法拒绝，需独立编辑器合同 |

优先级表示迁移与可信事实发布的处理顺序，不表示安全漏洞等级。
已接受的数字符号不吸收策略不属于缺陷，也不建议改回。

建议先修复范围与后缀这两项明确的 Java 适配缺陷，再实施“局部恢复身份 + 结构化诊断关联”的完整合同。
对表达式槽位可以复用已有 `ErrorExpression`，对缺名后缀可以复用已有 `MissingAttributeStep`；
类型槽位、参数顺序、缺闭合符等不能单靠这些节点表达，需要恢复旁路或更大的 AST 演进。

## 2. 基线、过程与证据边界

### 2.1 基线

- gdparser HEAD：`d2c3ef355530a2c87697b912b3c3d49f6a9aa475`，与 GDCC 回归报告的 0.7.0 源码基线一致。
- Godot：`vendor/godot/COMMIT_SHA` 固定为 `c016f22009aa773f0b2e6fce72baec168746491f`。
- 实际复现针对当前源码编译产物，不是远程已发布 0.7.x 工件验收。
- 文档入口：`doc/godot-java-parser-implementation.md`、`doc/architecture-overview.md`、`doc/tree-sitter-ng-gdscript-implementation-plan.md`。
- 通过 MCP 列出了 `doc` 和 `doc/module_impl`；后者不存在，不存在遗漏的该目录合同。
- 已有三个 `AD` 状态的 Scratch 测试未改动；未提交 Git 变更。

### 2.2 调研过程

先并行探索文档、共享 AST/CST 映射、声明解析、表达式解析、测试与 Godot 源码，再拆分小范围 `general` 子代理分析与测试。
没有使用 `explore` 子代理做评审或根因分析；本轮不需要复杂的 `review-expert` 任务。
子代理环境未提供 IntelliJ MCP，主代理补查了 parser、facade 与 mapper 的 `get_file_problems`。
这些生产文件没有 IDE ERROR，只有既有 WARNING，未进行无关清理。

### 2.3 三种证据必须分开

| 证据 | 本轮实际执行 | 可得结论 |
|---|---|---|
| 当前 gdparser 最小复现 | 是，48 例纯 Java source-launch driver | 当前 parser 的诊断、strict、AST、父子包络与显式错误标记 |
| Godot vendor 源码 | 是，固定提交静态追踪 | parser 分支、恢复分配器、返回状态与 completion hook；不是二进制/完整 analyzer 实测 |
| GDCC registry/sema/补全 | 未重新运行 | 下游运行后果引用原报告；相关消费顺序和消费点另有只读源码核对 |

没有重跑 0.6.0 工件逐例对照、Godot 二进制、GDCC lowering/backend/runtime。
不能把“当前正常节点被 GDCC 发布为 RESOLVED”推断成“已经生成错误二进制”。
原报告 D/A 日志是新入口单侧，不能把其中每一项都说成已有新旧运行对照的回归。

### 2.4 本轮产物与运行结果

研究驱动：`tmp/probes/PureJavaRecoveryResearchProbe.java`。
原始日志：`tmp/probes/pure-java-recovery-research.log`，下文简称 **L**。
这些是观察记录，不是把当前错误形状认可为正确行为的验收测试。

```text
SUMMARY cases=48 strictSuccesses=20 explicitMarkers=0 containmentViolations=1 unexpectedExceptions=0
```

20 个 strict 成功案例包含合法控制，也包含本轮审计的零诊断输入，不能当成 20 个正确性 PASS。
包络违规没有豁免；本轮仅证明这 48 例中有 1 处违规，不证明全仓只有这一处。

## 3. 当前报错与错误节点策略

### 3.1 现行文档的明确决定

`godot-java-parser-implementation.md:102-103` 和 `architecture-overview.md:187-191` 明确规定：

- 纯 Java 路径不构造 `ErrorStatement`、`ErrorExpression` 或 `MISSING`-kind issue。
- 失败构造可以丢弃，也可以返回部分正常节点或空 Block；非空 AST 组件不留 null 洞。
- 非法源不要求新旧 AST parity，最低验收为终止、不抛异常、有 ERROR 和有效诊断范围、恢复后的兄弟结构正确。

`godot-java-parser-implementation.md:34` 又说明现有 Error 节点的 kind 语义通用。
因此“不构造”是**当前产出政策**，不是 Java 类型系统不允许纯 Java parser 复用这些类型。
把它当成永远不能改变的技术约束，会错过已经存在的恢复表达力。

现行最低非法源验收不足以保障 IDE 可信事实；冒号/空默认值的静默接受也说明：
“非法源均有诊断”不能取代逐项审计语言接受面。

### 3.2 诊断与 panic 状态不等于恢复身份

`GdScriptParser.java:317-331` 的 `pushError` 总是追加 `AstDiagnostic`，并置 `panicMode=true`。
语法错误的 `nodeType` 固定为 `"ERROR"`；无 origin 重载锚在 `previous` token。
`scanSkippingErrors` 将 tokenizer ERROR 转为诊断，parser 本身不接收 ERROR token。
`synchronize` 消费到边界并清除 panic 状态，继续解析兄弟。

`AstDiagnostic.java:4-9` 只有 `severity/message/nodeType/range`；
`AstMappingResult.java:6-10` 只有 `ast/diagnostics`。
没有稳定错误码、根因 ID、受损槽位、最终 AST 节点关联、源级槽位存在性或闭合完整性。
panic 是游标恢复控制状态，不会随返回结果交给消费者。

### 3.3 当前三种输出

| 输出 | 例子 | 现有可用信息与缺口 |
|---|---|---|
| 丢弃构造 | if 缺必要条件、`super.`、失败实参导致整个 call 消失 | 诊断仍在，但源构造/补全锚点可能不在 AST 中 |
| 保留部分正常节点 | 默认值/initializer/return 为 null，数组或注解少了实参 | 健康部分存活，但 absent 与 malformed 合并，位置/数量意图可能改变 |
| 保留正常值或前缀 | 缺 `)` 的 grouping/call、失败 cast 返回 left、缺名后缀仍 fold | 有时保留可分析前缀，但受损边界不可辨，可能被当完整表达式消费 |

字典另用 `LiteralExpression("null", "", zeroWidthRange)` 合成缺失侧，既不属于真实源码 literal，也没有错误身份。
零宽范围、空文本或合成文本本身不能充当通用错误判据：合法 Lua key 本来就会归一化为合成 `string_name` 文本。

### 3.4 tree-sitter 路径的差别

`CstToAstMapper.java:557-563` 分别将 ERROR/MISSING 映射成 `ErrorExpression`。
`901-918` 的默认参数 value 缺失会进入 `mapRequiredExpression`；
`1087-1113` 构造零宽 `ErrorExpression(MISSING)` 并发诊断。
悬空属性链还使用 `MissingAttributeStep` 保留健康 receiver。

这些节点可以被 AST walker、serde 和下游错误子树标注看到。
这解释了为什么仅把纯 Java AST 交给原来的错误子树标注器，不能自动获得原来依赖显式错误节点的恢复能力。
但 mapper 的一般分支不能替代每个具体非法输入的旧版本运行证据。

### 3.5 严格模式与 IDE 必须是两个合同

`GdScriptParserFacade.java:34-44` 的 strict 只依据第一个 ERROR 抛 `IllegalStateException`；WARNING 不触发。
因此对于本轮**已有 ERROR**的案例，strict 门禁确实拒绝；对于零诊断案例，strict 仍成功。
raw `tokenize` 包含未经 parser multiline 干预的布局流，不能用它的 ERROR 数作为源合法性裁判。

- 编译路径：含 parse ERROR 不得进入 lowering/backend。
- IDE 路径：允许容错 AST 和健康兄弟继续，但不能把受损签名、属性元数据、整链类型或 callable boundary 发布为完整可信事实。

整个文件一有 ERROR 就停止所有 IDE 分析，会掩盖发布问题，却丢掉本来健康的 body、兄弟与其他文件，不是推荐方案。

## 4. 为什么不能只移植 Godot 的正常节点形状

Godot 本身也大量返回 null、部分节点或正常 operand，但还有下列配套信息：

| 机制 | 固定源码证据 | 本项目适配差异 |
|---|---|---|
| parse 成败单独返回 | `gdscript_parser.cpp:533-537`，有 errors 返回 `ERR_PARSE_ERROR` | facade 也可 strict 拒绝，但容错消费者不能只看 AST 类名 |
| 合成恢复节点有特殊 extent | `gdscript_parser.h:355-359,1525-1534`，位置默认 -1，恢复 allocator 不重置 extent | Java 一律映射物理 UTF-8 Range；字典 dummy 因此需要新范围与身份政策 |
| 注解语法 invalid | `gdscript_parser.cpp:1900-1904,1924-1928`，坏实参置 valid=false，最终返回 nullptr | Java 删除坏实参后仍无条件返回普通注解 |
| 参数顺序错误跳过参数 | `gdscript_parser.cpp:1723-1749` | Java 继承；不表示截短后的签名可安全被 IDE 当完整签名 |
| 变量记录 assignment | `gdscript_parser.cpp:1284-1290`，匹配 `=` 后递增 assignments | Java `VariableDeclaration` 只有可空 value，没有等价源级意图信号；assignments 也不是完整损坏合同 |
| AST 外 completion context | `gdscript_parser.cpp:379-446,3475-3493`，先登记 SUPER_METHOD 再可能返回 nullptr | 本项目已裁剪 hook，只保留一般 AST 恢复不能提供同等编辑器信息 |

这些观察不等于“Godot 完整编译接受零诊断缺类型/缺默认值”。vendor 未包含本轮执行的 analyzer/编译链。

## 5. 分项核对与根因

以下 Java 行号均指 `src/main/java/dev/superice/gdparser/frontend/gdscript/GdScriptParser.java`。

### 5.1 冒号后缺类型：零诊断，不是 strict 失效

原报告四例均复现：`func f(x: ): pass`、`signal s(x: );`、
`var f = func(x: ): return x`、`func f(x:, y:int):pass`。
L:1-16：四例都是 0 ERROR、0 WARNING、strict 成功、`type=null`。

根因：`parseNameAndType:2097-2109` 已消费 `:`，但不检查 `parseType(false)` 的 null；
`parseTypeInternalImpl:1864-1865` 把“缺类型”消息留给调用者。
vendor `parse_parameter:1537-1545` 与 `parse_type:3845-3862` 有同一路径。

原报告的“相对旧入口诊断丢失”口径成立；不能说成 Java 独有移植失误。
GDCC 的 null-to-Variant 对合法未标注参数合理，不能全局删除 fallback。

建议：明确是否要求消费冒号后的命名类型必需存在；若要求，应补 ERROR 并保留参数/健康 body。
`:=` 必须先识别为合法推断分支，不能把 `func f(x:=1)` 误拒绝。
若决定沿用 vendor 接受，应把这种源级意图丢失登记为接受面变化，不能默认其符合旧消费者合同。

### 5.2 默认值损坏与参数顺序：源级签名被改写

`func f(x: int = 1+): return x` 的内层表达式报错，但参数 `defaultValue=null`，L:21-25。
`parseParameter:2147-2152` 没有记录匹配过 `=`，与根本没写默认值同形；vendor `1548-1551` 也如此。
原报告记录的“调用不传参数时派生必填错误”是 GDCC 后果，本轮仅复现 parser 输出，不泛化为所有调用失败。

`func f(a: int = 1, b: int) -> int: return b`，L:26-30，参数列表只剩 a，body 的 `return b` 仍在。
`parseFunctionSignature:2320-2323` 用 `continue` 跳过 b；vendor `1735-1738` 同样跳过。
rest 后参数和重复参数也会消失，L:52-61；这不是随机恢复行为。

建议保留可识别参数的位置/名字/类型，另标签名受损；默认值必须区分 absent、present-valid、present-malformed。
参数顺序非法不应冒充“参数不存在”，但重复名保留也不能让 binding 任意覆盖前一个参数，需要确定冲突绑定策略。
body binding 可以继续；可信 overload/arity、默认值可用性和完整签名发布必须独立关闭。
这是非法源恢复增强，可以有意偏离 vendor，不违反合法源 parity。

### 5.3 注解实参删除：裁剪语义验证时丢了语法 invalid

L:76-92：

| 输入 | 当前幸存实参 | ERROR |
|---|---|---|
| `@export_range(0, 1+, 2)` | `[0,2]` | 2 |
| `@export_enum("A", ,"B")` | `["A","B"]` | 1 |
| `@export_range(1, =3)` | `[1]` | 2 |

`parseAnnotation:1811-1824` 只追加成功实参，最终总是返回普通 `AnnotationStatement`。
vendor 则在坏实参时置 `valid=false` 并最终返回 null。
注册表、合法 target、arity/literal 语义验证可以继续裁剪；**实参语法损坏状态不能一起裁掉**。

原报告中 hp/he 获得正常 export 元数据、hr 追加 arity 报错，来自 GDCC 运行证据。
仅按压缩后的实参数量验证，会把坏源码投影成未真实写出的注解。

建议保留注解名字与实参位置供 IDE 展示，但停止该条坏注解的投影和派生 arity 校验。
健康宿主 property 不受坏注解连带剔除。
如选择 vendor 风格丢弃整条注解，必须另保留源级恢复/补全信息，不能只静默删节点。

### 5.4 初始化器损坏：保留声明合理，发布属性需独立门禁

L:97-107：`var hp = 1+` 产出普通 `VariableDeclaration(value=null)`，健康 mp 保留；
`var hp = (1` 报缺 `)`，value 仍是 `LiteralExpression(1)`。
根因分别是 `parseVariable:1950-1969` 和 `parseGroupingExpression:2662-2670`。
vendor `parse_variable:1284-1304`、`parse_grouping:3358-3366` 有对应恢复行为。

不能用 `value==null` 判断坏声明，合法 `var hp` 同样为 null；
也不能只用 value 非空就判断健康，缺闭合分组恰好非空。

原报告指出 GDCC skeleton 先于错误子树标注，坏 hp 已经注册。
本轮只读核对的消费点为 GDCC `FrontendSemanticAnalyzer.java:207,223`、
`FrontendClassSkeletonBuilder.java:768-814`，下游运行结果仍引用原报告 §5。

建议保留声明名字/已识别类型作为 provisional symbol，初始化器 facet 标为受损；
用于导出/编译的 property registry 不发布它为有效属性，IDE 本地查找可保留失败或暂定符号以减少级联。
可信度信息必须在 skeleton 填成员/投影注解之前可消费，不能指望后置 skipped 标记撤销已经发布的事实。

### 5.5 字典占位父子越界：独立范围缺陷

输入 `var d={"a":};`，L:112-117：

```text
DictionaryExpression [6,12)
  DictEntry [7,10)
    key   LiteralExpression("string", "\"a\"") [7,10)
    value LiteralExpression("null", "")        [11,11)
```

`2779-2781` 用 key 末尾结束 entry；`2795-2796` 用当前 `}` 的起点锚定占位，因此直接子节点越界。
vendor `3335-3346` 也插 dummy，但使用保留 -1 extent 的 recovery allocator，不能直接照搬成正常物理 literal。

两件事分开修：

1. 包络：在已消费分隔符后锚定缺失 value 的插入点，并让 entry 范围包含该点；或采用明确的父内锚点。不得改成负字节位置。
2. 身份：缺 value 不是用户写出的 null，应用错误占位或结构化 recovery marker 标明，不能作为可折叠 null 消费。

统一不变量为 `parent.startByte <= child.startByte && child.endByte <= parent.endByte`。
零宽 child 恰好落在 parent.end 可以成立，不要求所有零宽节点都能被半开 nodeAt 选中。
缺 key 的 `var d={:1};` 本轮没有越界，L:118-122，不应混为同一范围缺陷。
合法显式 null 和 Lua 合成 key 控制均零诊断，L:123-130。

### 5.6 缺名点号后的误折叠：健康前缀不能代替受损整链

L:136-145：`self.b.(1)` 变成 `AttributeCallStep("b", [1])`；
`self.p.[0]` 变成 `AttributeSubscriptStep("p", [0])`，各有一条 ERROR，但没有 missing step。

`parseSuffixChain:3131-3145` 在缺名时只报错，不加步骤或 barrier；
`3153-3156,3178-3181` 只检查最后步骤是不是 property，再把后续调用/下标折入它。
vendor `parse_attribute:3394-3397` 保留带 base 的失败 SubscriptNode，后续不会直接折到前一个有效名字。

当前测试 `GdScriptParserTest.java:1628-1650` 还明确断言这种误折叠，因此测试通过不证明恢复正确。
修复时应先明确“不重绑定”的语义不变量、修实现，再更新原错误策略对应的断言并增加控制例；不能只是改测试接受另一种随意形状。

建议在**每个缺名点号**产出 `MissingAttributeStep` 或等价显式障碍，不只在下一步即将 fold 时才标记。
可继续消费后缀以保留编辑上下文，但 call/subscript 不得跨障碍折进 b/p。
正常 `self.b(1)`、`self.p[0]` 必须保持原形状与零诊断；健康 receiver/property 前缀仍可分析。
`a.`、`a.b. .c` 同样需要受损步骤身份，不能留一个未标记的“末尾删除点号”特例。
`..` 是单个 token，不属于本项两个独立点号的恢复。

### 5.7 坏 return 与诊断归属

L:178-191：`return =3`、`return 1+` 各有一条 ERROR，但与合法裸 return 一样 `value=null`。
根因是 `1227-1243` 不区分“根本未写值”和“尝试带值但失败”。vendor `2108-2128` 有同形 AST 恢复。
原报告记录 GDCC 追加“int 返回槽不能裸 return”；这是恢复派生错误，不是独立裸 return 类型错误。

循环外 break/continue 的普通节点仍保留，parser 各发 ERROR，L:192-197；
这些检查是现行 §5.6 已明确移植的检查，不建议为了去重直接从 parser 删除。
嵌套容器 cast 的 TypeRef 也保留，L:202-206；parser 在 `1879-1881` 报错但不置 failed。
原报告的 nested cast parse/sema 范围不同，不能按范围相等去重。

建议坏 return 值携带失败槽位，不再被当作裸 return；
break/continue、nested collection 的同根因检查通过 code/construct/diagnostic 关联决定 owner。
GDCC sema 对手工 AST、旧路径或没有关联元数据的输入仍必须能自行校验，不能删掉全部检查。

### 5.8 `super` 与未完成调用：独立补全信息不可由普通 AST 推导

L:154-177：`super.` 和 `super.ba` 都不产出 probe body 语句；健康后续函数完整存活。
`parseSuperExpression:2920-2934` 缺名字、缺 `(`、实参失败均返回 null。
vendor `3475-3493` 也可能返回 null，但此前已登记 `COMPLETION_SUPER_METHOD`。
该信息存在 `gdscript_parser.h:1361-1376` 的 completion context，而不要求返回的 AST 有 receiver。

纯 Java facade 没有 completion 入口；补全 hook 已在文档 §1.1/§6/§11 明确裁剪。
因此不能将它说成新 parser 拒绝合法 `super.ba` 语句，也不能声称裁剪后仍有 Godot 编辑器等价能力。

未完成调用的本轮观察，L:212-236：

| EOF 尾部，无最终换行 | AST |
|---|---|
| `target(` | initializer=null，没有 CallExpression |
| `target(1,` | initializer=null，没有 CallExpression |
| `target(1` | 保留 CallExpression(args=[1])，缺 `)` 诊断仍在 |

`parseCallArguments:3235-3246` 任一参数失败就返回 null，缺闭合符本身却不置 failed。
vendor `3526-3553` 即便有失败实参仍返回 CallNode，这是 Java fail-closed 适配，不是合法源 parity 缺陷。

建议恢复旁路独立携带同代 source/cursor 的 receiver、词法超类语义、callee、call 范围、active argument 和闭合状态。
可以保留 partial CallExpression，但其候选签名/参数提示与“已完整解析调用”应分开。
super 的候选应从词法超类枚举，不从当前类类型枚举。
调用上下文分类不是完整 signature help 验收；现有接口缺少 signature DTO 是另一个能力边界。

## 6. 本轮补充发现

### 6.1 空默认值也零诊断

以下为四个独立脚本，L:31-46 均 0 ERROR、0 WARNING、strict 成功：

```gdscript
func f(x = ):pass
```

```gdscript
func f(x:=):pass
```

```gdscript
signal s(x = );
```

```gdscript
var f = func(x = ):return x
```

原因比“有内层错误但默认值丢失”更直接：`parseParameter:2148-2150` 在 `=` 后调用表达式，
对 `)` 没有 prefix 规则而返回 null，调用者也不补“必须有默认表达式”的诊断。
外层却能继续消费 `)`、`:` 和 body，因此 strict 没有 ERROR 可拦。
vendor `parse_parameter:1548-1551` 同构；不据此推断其完整 analyzer/编译接受。

signal 的 default 禁止检查只看 `defaultValue!=null`（`2180-2182`），空默认值还绕过该检查。
`signal s(x = 1+)` 则有一条内层 `+` ERROR，L:47-51；不能误写成“所有损坏 signal 默认值零诊断”。

建议匹配过 `=` 就记录 default intent，表达式缺失时补根因 ERROR；
有内层根因则建立关联，不必再叠加同槽位的泛化错误。
`:=1` 是合法控制，`:=` 缺 value 不应因为存在推断 TypeRef 就被视为完整。

### 6.2 变量缺类型存在分号边界

`var x:;`，以及函数体中的 `var x:;` 均零诊断、strict 成功、`type=null`，L:62-69。
`parseVariable:1925-1946` 只对 NEWLINE 分支专门处理缺类型/属性体，其他非类型 token 的 null 返回不检查。
vendor `1255-1280` 有同构分支。

这与顶层 `var x:` 后跟真实属性 getter/setter 的合法结构不能混淆。
建议在属性和 `:=` 分支排除后，要求消费过类型冒号的分支有 TypeRef，覆盖 semicolon/EOF 等边界。
`const C:;` 本轮有两条 ERROR，L:70-75，不能从复用同一 helper 推断常量例也静默接受。

### 6.3 parser 自身也有同槽位双报

`@export_range(0, 1+, 2)` 的 `+` 内层错误和“Expected expression as the annotation argument.”
锚在同一 `[18,19)`，L:78-79。
`var hp = 1+` 的 `+` 内层错误和“Expected expression for variable initial value...”
锚在同一 `[10,11)`，L:99-100。

这来自表达式内部和调用者分别 `pushError`，且 `pushError` 总追加。
与 GDCC 双报是两个层次；不能只解决跨阶段重复而不检查 parser fallback 的归属。
不是所有多个诊断都应删除：独立缺闭合符或另一个槽位的错误仍可保留。

## 7. 解决方案：局部恢复身份与可信度

### 7.1 必须增加的合同，不依赖最终载体

1. 合法 absent 与源码 present-but-malformed 可辨，尤其 type/default/initializer/return/argument。
2. 恢复不改变参数/实参/元素的源级位置意图；丢弃节点时保留对应槽位信息。
3. 健康 prefix、body binding 和兄弟可继续；受损整构造的可信语义事实不得发布。
4. 每个缺名点号形成后缀折叠障碍，不能跨受损步骤绑定到有效属性。
5. 所有物理 Range 自洽，合成零宽节点在父范围内；合成身份另行表达。
6. diagnostics 用稳定 code、根因和构造关联确定 owner，范围只用于定位，不承担唯一身份。
7. 该信息在 skeleton/registry 消费之前可用，并与同一次源码快照绑定。
8. 编译拒绝含 ERROR；IDE 只降级受损能力，不能全文件 fail-open，也不能全文件停分析。

### 7.2 方案比较

| 方案 | 优点 | 缺口/成本 |
|---|---|---|
| A：仅复用已有 Error/Missing 节点 | 默认值/initializer/return/实参等表达式槽位直接可辨，AST serde 可保留；missing step 可阻止后缀误 fold | TypeRef、参数顺序、缺闭合符、丢弃的 super 上下文不能单靠它表达；owner 关联仍不足 |
| B：普通 AST + typed recovery sidecar | 保持 AST record/schema/合法 parity，能表达类型/签名/边界等 facet；符合现行纯 Java 不产 Error 的政策 | 新结果/API传播合同；只交 SourceFile 或只 serialize AST 会丢 sidecar；消费者必须显式前置消费 |
| C：错误槽位用已有 Error/Missing，其余 facet 用 typed sidecar | AST 内能保留必要缺失身份，旁路补足正常存活构造/未闭合/诊断归属；不需新增 AST 节点类型 | 两种载体需定义单一根因及派生规则；改现行产出政策与结果/API合同，消费集成面较大 |

**推荐完整方向 C，但分批交付，不先扩充所有正常 record。**
如果必须维持“纯 Java 不产 Error/MISSING”的现行决定，B 也可成立；不能假装仅补诊断就已经实现 B。
若新增统一 AST 恢复字段/专用节点，则是另一个更大方案，应另行评估协议与下游兼容，不作为本轮默认最小方案。

### 7.3 复用已有节点不等于改 serde schema

`AstSexprSchema.java:109,122,138` 已注册 `ErrorStatement`、`MissingAttributeStep`、`ErrorExpression`；
现有 sealed hierarchy、walker、handler 和 serde 已认识它们。
因此**单纯在非法源恢复分支构造这些已有 record，不需要新增 schema 注册或改合法 fixture 的快照**。
需要修改的是纯 Java 的产出契约、构造点、相应错误恢复测试及消费策略。
合法输入不进入恢复分支，合法 AST parity 可以保持；必须用零诊断控制测试保障守卫正确。

不能用 ErrorExpression 填 `TypeRef`：二者类型不同；
也不能用 UnknownExpression 代替语法错误，它表达的是“未知/不支持”，会混淆现行 warning/error 语义。
`CstIssueKind` 虽在 cst 包，但只是 `ERROR/MISSING` enum，复用它不要求 native runtime；本轮无需为命名纯化做包迁移。

对于完全缺表达式的槽位可用 MISSING，已有坏 token 的片段可用 ERROR，正常 null 只留给真的 absent。
不能把整个受损声明换成 ErrorStatement 来省事，那会再次丢掉健康名字、类型、body 与 prefix。

### 7.4 恢复旁路的最小信息

建议按构造和 facet 发布，而不是给整个函数一位 `damaged=true`：

| 信息 | 用途 |
|---|---|
| source snapshot/revision 与本次解析内 construct identity | 防止跨编辑版本关联及重建后的错配 |
| owner/最终 AST 关联或未产出构造的锚点 | 定位正常存活节点、被丢弃 super/call、失败 cast 源后缀 |
| facet | 区分 TYPE、DEFAULT、INITIALIZER、ANNOTATION_ARGUMENTS、SIGNATURE_ORDER、SUFFIX、DELIMITER 等能力 |
| 源级槽位存在性、位置、完整性/受损原因 | 不再把损坏默认值当 absent，也不压缩实参位置意图 |
| 完整 source range 与诊断 IDs/code/root cause | 节点范围可只覆盖健康 operand；错误范围仍能包括 `as`、点号、缺闭合上下文 |

具体类型与公开 API 尚需决策，表格不是已实施字段定义。
本方案建议稳定 code/root-cause 和诊断关联先承载在详细结果的 sidecar 中，不直接扩充 `AstDiagnostic` record；
通过本次解析内的显式关联定位最终冻结列表中的诊断，而不是靠消息或范围反查。
若选择把 code/ID 直接加到 `AstDiagnostic`，同样会改变其公开构造器并影响 tree-sitter 构造点和外部消费者，
应单独纳入 API 迁移决策，不能只计算 `AstMappingResult` 的成本。
短期关联可以在同次构建中用身份表，但不能使用 `HashMap<Node,...>` 的 record 结构相等语义冒充对象身份。
IdentityHashMap 又会在 AST 重建/反序列化后失效，需要明确重建传播或最终树路径/ID的传输合同。

不要在方法进入/退出时只看诊断数变化就污染整个函数：
lambda 默认值内部 body 的独立错误、兄弟语句错误与外层签名可信度不应被粗粒度合并。
在具体槽位消费前登记源级意图，表达式返回后按槽位归因；非空结果也需保留 delimiter/validation 损坏。

`AstMappingResult` 现在是两组件 record，直接加组件会改变构造器及公开 API，GDCC 是已知外部消费者。
可选择演进结果类型或增加详细解析结果入口，但不能宣称“加 sidecar 完全无兼容成本”。
本轮不替用户承诺兼容桥或更改公开 API。

`AstSexprSerializer.serialize(SourceFile)` 不序列化 diagnostics/sidecar。
因此仅传 AST、serde 往返或重建 AST 都不能默认保留完整可信度；需要同时传输详细结果，
或明确序列化 AST 只能用于受 ERROR 门禁保护的合法输入。
复用 Error 节点只解决其中一部分，不能宣称 C 已自动持久化所有恢复信息。

### 7.5 诊断 owner 与消费者能力门禁

- parser 保留确定的语法/已移植上下文检查；使用稳定错误码和 construct/facet/root-cause 关联。
- 已有子表达式根因时，调用者 fallback 可关联而不再独立展示同根因泛化消息；确实没有根因时必须补缺表达式错误。
- 缺闭合符、不同槽位、独立语义错误仍可独立报告，不按全局 panic flag 或英文前缀吞错。
- sema 只抑制已有结构化关联证明的同根因；不简单按 range 相等，也不删除手工 AST 入口需要的检查。
- 签名 DEFAULT/ORDER 受损：保留名字/参数 binding，停止可信 arity/overload/exact callable 发布。
- 注解 ARGUMENTS 受损：只停该注解投影，不停健康宿主 property；参数数量不能用删槽后的列表制造派生错误。
- property INITIALIZER/TYPE 受损：可保留 provisional symbol，不放入有效导出/编译 registry；健康 mp 正常发布。
- return VALUE 受损：保留返回语句失败事实，不能再按 bare return 检查。
- suffix 受损：健康 receiver/prefix 可以提供事实，受损整链不能发布正常完整解析结果。
- grouping/call DELIMITER 受损：健康操作数/候选仍可查询，但不冒充完整 initializer/call。

以上门禁需在 GDCC skeleton 之前消费；增加 ErrorExpression 并不会自动改变现有执行顺序。
单靠 parser 修改不能完成 GDCC registry、诊断 owner 与补全集成验收。

## 8. 分批实施建议与验收

### 8.1 可独立开展的确定性修复

1. 修字典缺 value 包络，加入 AST 直接父子范围不变量；不改变健康 null/Lua key，不添加豁免。
2. 修缺名点号 folding barrier；保留每个缺失步骤，不再回绑到 b/p；健康链与兄弟结构不变。
3. 补定向复现为正式 JUnit5 测试，断言诊断、恢复身份/形状、范围、健康兄弟，而非只断言消息存在。

具体用 Error/Missing 还是 sidecar 表达恢复身份，应先接受相应政策。
范围修复自身可以先交付，但**仅修范围不等于已解决合成 null 的可信度**。

### 8.2 零诊断接受面决策后

建议选择“显式消费类型/default 标记后，必需部分缺失就报 ERROR”的工程合同，并登记为对 vendor 的有意偏差。
覆盖函数/信号/lambda 参数、变量分号/EOF、推断标记缺初始化器；属性 getter/setter 与合法 `:=1` 保持不变。
常量等同 helper 使用点也要审计，但不能先把未证实例子写成零诊断缺陷。
用户若选择沿用 vendor 接受，则需另定义下游类型意图和 default intent，不得悄悄映射到 Variant/必填。

### 8.3 恢复身份与消费联动

先确定结果/API、facet 和 root-cause 合同，再更新槽位构造、signature 保留与消费者的前置门禁。
分开验收健康 body binding、provisional symbol、有效 registry、可信调用事实和诊断输出，
不能以“已经给节点加标记”代替具体消费点验收。
随后补 super/call 的独立编辑上下文，不因支持 partial 查询而放松 strict 编译门禁。

### 8.4 验收矩阵

| 场景 | gdparser 应验收 | GDCC 联动应验收 |
|---|---|---|
| 参数缺类型、空默认值 | 按明确接受策略有 ERROR，strict 拒绝；参数/健康 body 保留，`:=1` 干净 | 不静默发布 Variant/必填完整签名；健康参数 binding 可用 |
| 默认值 `1+` | 源级 default 存在但受损可辨，根因关联 | 不发 missing-required 派生错误，不发布可信 arity |
| optional 后 mandatory/rest 后参数 | 参数 identity/位置保留，签名受损；重复名不任意覆盖 | b 的健康引用可绑定；坏签名不冒充少参数 overload |
| 三种坏 export 注解 | 坏槽位/注解完整性可见，宿主仍健康 | 不投影损坏元数据、不报删实参造成的派生 arity |
| `var hp=1+`、`var hp=(1` | 分别可辨 failed expression 与 delimiter damage；mp 保留 | skeleton 前有状态，坏 hp 不进有效 registry |
| 缺 key/value 字典、坏数组 | error slot/源级位置可辨，全部父子包络正确 | 不把合成 null/压缩元素视为可信容器值 |
| `self.b.(1)`、`self.p.[0]`、`a.` | 受损 step 存活，绝不跨 step fold，合法链不变 | 健康前缀可分析，整链不发布 RESOLVED/可信 callable |
| 坏带值 return 与真 bare return | 两者可辨；真 bare return 仍保持正常语法 AST | 坏值不派生裸 return 错误，合法裸 return 类型错误仍报 |
| loop control/nested cast | parser 根因与 owner 可关联，范围保持定位用途 | 同根因不双报；独立/手工 AST 校验不丢 |
| super 与三种 incomplete call | AST 或旁路保留 prefix/callee/argument/closing intent，健康 sibling 保留 | 词法超类候选、active argument 与签名 DTO 单独验收 |
| 普通合法语料 | 零 ERROR、合法 AST/serde/范围不变、共同支持语法 clean parity | 有效 registry/调用解析不回归 |

覆盖 CRLF、中文/emoji 字节位置、EOF 无最终换行、插入点落在 parent.end、nested lambda/default、
坏构造之后的健康函数/成员及深度预算原有护栏。
数字符号策略、key-only match pattern、已登记的病态 continuation 限制不混入此次修复目标。

### 8.5 实施前须确认

1. 接受面：缺 type/default 是否统一拒绝，还是忠实沿用 vendor parser 接受？推荐前者，明确记录偏差。
2. 恢复载体：是否接受修订“不构造 Error/MISSING”决定？推荐已有错误槽位节点 + typed sidecar；保持决定则选完整 sidecar。
3. 发布边界：provisional symbol 与有效 property/callable registry 如何分离，以及 GDCC 是否同步前置消费？
4. API/传输：详细结果怎么公开，旧 AstMappingResult/GDCC 工件怎么迁移，sidecar 是否需要序列化？
5. 编辑器范围：super 前缀与 signature help 是否作为此次迁移交付的一部分，而不是假定现 parser 已提供？

这些决定未确认前，不应直接修改 parser 政策、共享 record 或把建议写入事实源。

## 9. 本轮实际验证

定向测试由子代理运行一次 wrapper：

```bash
script/run-gradle-targeted-tests.sh --tests GdScriptParserTest,GdScriptParserErrorRecoveryTest,GdScriptParserFacadeTest,GdParserParityTest,CstToAstMapperErrorRecoveryTest,AstSexprSerdeTest
```

| 测试类 | 执行数 | failures/errors/skipped |
|---|---:|---|
| GdScriptParserTest | 224 | 0/0/0 |
| GdScriptParserErrorRecoveryTest | 47 | 0/0/0 |
| GdScriptParserFacadeTest | 11 | 0/0/0 |
| GdParserParityTest | 35 | 0/0/0 |
| CstToAstMapperErrorRecoveryTest | 27 | 0/0/0 |
| AstSexprSerdeTest | 19 | 0/0/0 |
| 合计 | 363 | 0/0/0 |

`GdParserParityTest` 的动态 fixture 子例计入 35 个执行，不能与文档的方法数口径直接混用。
`BUILD SUCCESSFUL` 不证明当前恢复具有 IDE 所需的可信度；原测试还固定了部分有问题的输出策略。

研究驱动已由主代理 apply_patch 创建、IDE 格式化；执行前调用 MCP build_project 成功。
`tmp` 不是模块源码根，IDE 对该独立 driver 会显示连 JDK/project 类型都无法解析的上下文错误；
没有修改项目配置来隐藏它们。driver 实际通过 Java 25 source launcher 编译运行：

```bash
java --class-path build/classes/java/main tmp/probes/PureJavaRecoveryResearchProbe.java > tmp/probes/pure-java-recovery-research.log
```

退出 0、48 例均无异常；L:241 是汇总，L:242 明示这不是正确性验收。
本轮未跑全量 suite/clean build，没有正式依赖升级或 Godot/GDCC 编译执行。

## 10. 关键源码索引

| 文件 | 重点行号/内容 |
|---|---|
| `src/main/java/dev/superice/gdparser/frontend/gdscript/GdScriptParser.java` | 317-331 diagnostics；491-544 synchronize；1227-1260 return/loop control；1797-1825 annotation；1855-1897 type；1916-1972 variable；2097-2153 name/type/parameter；2157-2200 signal；2290-2358 signature；2662-2797 grouping/array/dictionary；2907-2939 super；3029-3060 cast/type-test；3121-3247 suffix/call |
| `src/main/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserFacade.java` | 25-44 tolerant/strict，46-63 raw tokenize |
| `src/main/java/dev/superice/gdparser/frontend/ast/AstMappingResult.java` | 6-10 结果与冻结诊断列表 |
| `src/main/java/dev/superice/gdparser/frontend/ast/AstDiagnostic.java` | 4-9 现有诊断组件 |
| `src/main/java/dev/superice/gdparser/frontend/ast/Parameter.java` | 6-12 可空 type/default，未承载 source intent |
| `src/main/java/dev/superice/gdparser/frontend/ast/ErrorExpression.java` | 3-8 已有 ERROR/MISSING 表达式占位 |
| `src/main/java/dev/superice/gdparser/frontend/ast/MissingAttributeStep.java` | 4-6 已有缺失步骤 |
| `src/main/java/dev/superice/gdparser/frontend/lowering/CstToAstMapper.java` | 557-563 error dispatch；875-933 参数；1087-1113 必需槽位；1431-1440 partial chain |
| `src/main/java/dev/superice/gdparser/frontend/serialize/AstSexprSchema.java` | 109/122/138 已注册错误/缺失节点 |
| `src/test/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserTest.java` | 596-620 参数错误；703-714 注解；922-1034 容器恢复；1618-1650 悬空链/误 fold 断言 |
| `src/test/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserErrorRecoveryTest.java` | 152-189 value=null/grouping 恢复；209-228 未闭合 call；727-737 已登记深层 continuation 护栏 |
| `vendor/godot/gdscript_parser.cpp` | 379-446 completion；533-537 parse status；1237-1305 variable；1529-1555 parameter；1704-1756 signature；1851-1928 annotation；2108-2128 return；3230-3253 array；3335-3366 dummy/grouping；3369-3404 attribute；3450-3554 super/call；3845-3903 type |
| `vendor/godot/gdscript_parser.h` | 355-359 recovery extent；1361-1376 completion context；1512-1542 allocators |

外部参考：`../gdcc/doc/analysis/gdparser_0_7_pure_java_parser_regressions_report.md`，
§2 缺类型，§3 签名，§4 注解，§5 property 发布，§6 字典范围，§7 后缀回绑，§8 super，§9 诊断，§10 调用编辑，§12 验收边界。
这些原始调研资料不替代当前稳态文档，也不构成本报告方案已经实施的证明。
