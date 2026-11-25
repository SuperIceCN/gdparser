# gdparser 0.7.0 纯 Java Parser 缺陷与恢复回归补充调研

日期：2026-10-07。

本文补充 [LSP 迁移调研](gdparser_0_7_lsp_migration_research_report.md)，不是已实施合同。只更新调研文档、重编译并运行
`tmp/probes` 中的研究驱动；未修改生产源码、依赖、构建配置或既有测试期望。

**取消数字符号吸收已接受。`-2**2 -> -(2**2)` 是预期策略，仅为已知 Godot 差异，全文不将其列为缺陷、修复建议或迁移阻断项。**

## 1. 结论与证据口径

问题不只是“有没有报错”，还包括恢复后是否把坏代码发布成完整签名、真实属性元数据或可信表达式。需要区分三类：

- **Parser 自身的输出问题**：字典合成 value 的范围超出直接父节点；错误后缀被折到前一个有效属性名，且丢失受损步骤身份。
- **相对旧入口的诊断差异**：参数 `:` 后无类型时，新入口零诊断、strict 成功；参考 Godot parser 也有同一路径，不能称为 Java
  重写独有的错误。
- **GDCC 迁移适配回归**：损坏默认值变必填、参数被删除、注解截短后仍投影、坏 property 注册、return 级联诊断、super 补全前缀丢失，以及新增
  parser 检查与 sema 双报。

| 优先级   | 现象                             | 已验证后果                               | 分类                               |
|-------|--------------------------------|-------------------------------------|----------------------------------|
| P0 审计 | `func f(x: )` 零诊断              | strict 成功，GDCC 注册 `x:Variant` 并解析调用 | 旧入口诊断丢失，继承 vendor 行为；是否拒绝须明确语言策略 |
| P0    | 损坏默认值 / 非法参数顺序                 | 改写 arity，丢参数绑定，发布截短签名               | 恢复信息及 GDCC 签名发布合同不匹配             |
| P0    | 损坏 export 注解                   | 删除坏实参后投影为正常属性元数据                    | 恢复可信度丢失及 skeleton 投影回归           |
| P0    | 损坏属性初始化器                       | 坏 property 仍注册                      | skeleton 之前缺少恢复信息                |
| P1    | 字典缺 value                      | 合成子节点超出 `DictEntry` 范围              | Parser 范围不自洽                     |
| P1    | `self.b.(1)` / `self.p.[0]`    | 错误链与合法调用/下标同形，发布 RESOLVED 类型事实      | Parser 后缀恢复重绑定及消费者可信度问题          |
| P1    | `super.` / `super.ba`          | receiver 消失，成员上下文仍成立但候选为 0          | 编辑器恢复 / 补全回归                     |
| P1    | 损坏 return、loop control、嵌套 cast | 根因双报或派生诊断                           | 单 owner 合同回归                     |
| 后续能力  | 未完成调用参数提示                      | 有时无 CallExpression，有时分类为 UNKNOWN    | 原有功能缺口未解决，不等同于新的语法 bug           |

P0/P1 指 GDCC 默认入口迁移的处理顺序，不是已发布版本的崩溃或安全漏洞等级。

### 1.1 版本与执行环境

GDCC 基线为 `432f32ec32b2207db1db3bfb81a559fc5b3d6e9d`，当前仍依赖 gdparser 0.6.0。同级 gdparser 0.7.0 源码为
`d2c3ef355530a2c87697b912b3c3d49f6a9aa475`；参考 Godot vendor commit 为 `c016f22009aa773f0b2e6fce72baec168746491f`。

新入口直接编译源码，在隔离 classpath 中覆盖 GDCC 的研究版 `GdScriptParserService`。共享 AST 类型来自未改变的 0.6.0
工件；旧解析/补全路径仍加载 0.6.0 tree-sitter。不是正式依赖升级或远程 0.7.0 工件验收。

以下日志位于 `tmp/probes/gdparser07-integration/logs/`，后文用短名引用：

| 短名 | 文件                                                | 覆盖                                      |
|----|---------------------------------------------------|-----------------------------------------|
| S  | `syntax-and-range-defects-refreshed.log`          | 15 例；新旧 parser、strict、AST 范围逐例对照        |
| D  | `defect-examples-pure07-refreshed.log`            | 20 例；新入口接 GDCC parse/sema/registry/快照补全 |
| A  | `additional-defect-examples-pure07-refreshed.log` | 10 例；补充类型、默认值、链和注解投影                    |

源码与运行已同步刷新，三个驱动均退出 0。D/A 只记录新入口的下游行为，没有逐例旧入口对照；相对旧合同的回归判断还结合既有迁移测试和现行文档，不能把
D/A 当成三方实测。

示例保持日志中的 token、换行和缩进内容；坐标均为 UTF-8 字节。未运行 Godot 二进制，参考 Godot 结论是固定源码追踪，不是运行验收。

## 2. 参数冒号被消费，但缺失类型没有诊断

### 2.1 最小复现与新旧差异

下面四个片段应分别解析，不是同一脚本：

```gdscript
func f(x: ): pass
```

```gdscript
signal s(x: );
```

```gdscript
var f = func(x: ): return x
```

```gdscript
func f(x:, y:int):pass
```

新入口四例均为 0 ERROR / 0 WARNING，`parseStrict` 成功；`x.type == null`。旧 0.6.0 每例均为 1 ERROR / 1 WARNING，strict
抛解析错误。证据：S:7-99。

完整下游复现：

```gdscript
func f(x: ) -> int:
	return 1

func use() -> int:
	return f(2)
```

实际输出为 parse 0、sema 0，注册 `f(x:Variant)->int`，参数绑定为 `PARAMETER:x:Variant`，`f(2)` 为 `RESOLVED:int`。
`signal s(x: )` 也注册为 `s(x:Variant)`，parse/sema 均为 0。证据：A:2-62。

### 2.2 根因与不能过度推断的部分

`GdScriptParser.java:2097-2108` 的 `parseNameAndType()` 消费 `:`，调用 `parseType(false)`，却不检查返回的 null；`parseType`
把非类型 token 的错误消息留给调用者。参数 AST 因而无法区分“未写冒号”和“写了冒号但缺类型”。

GDCC `FrontendDeclaredTypeSupport.java:47-49` 对 null 返回 Variant 是合法未标注参数的正常合同，不能简单把这条 fallback
改成全局报错。

参考 Godot `gdscript_parser.cpp:1537-1545,3845-3861` 同样不补该诊断。**这是相对旧 tree-sitter 的诊断丢失，不是 Java
独有的移植失误。** 本次未验证完整 Godot analyzer/编译是否事后拒绝；不能声称“Godot 完整编译也接受”。

该例与其他有 ERROR 的恢复不同：解析 ERROR 门禁不会捕获这个零诊断输入。已验证的终点是 GDCC 共享语义成功与签名/调用事实发布，没有进行其
lowering/backend 编译。

### 2.3 建议决策与控制样例

若要求保留旧入口的严格诊断，应在已消费 `:`、未进入 `:=`、却无 TypeRef 时发布缺类型错误，并保留参数名/健康函数体；若要求忠实沿用
vendor parser 的接受行为，则应将其明确登记为语言接受面变化，而不是悄悄吞掉类型意图。本文不替用户决定这一策略。

- `func f(x:=1):pass` 新旧均零诊断，是合法推断默认值，不能误拒绝。证据：S:101-125。
- `func f(x:void):pass` 新入口有 ERROR，旧入口无诊断；不能归入零诊断缺类型问题。证据：S:127-148。
- `func f()->:pass` 新旧都有 ERROR；新入口会补缺返回类型诊断。证据：S:150-170。

## 3. 默认值与参数恢复改变了 callable 签名

### 3.1 损坏默认值变成正常必填参数

```gdscript
func f(x: int = 1+):
	pass

func use():
	f()
```

Parser 报 `Expected expression after "+" operator.`，但幸存的 `Parameter` 为 `defaultValue == null`，与从未写默认值相同。GDCC
注册 `f(x:int)->Variant`；`f()` 被解析为 FAILED，追加 `missing required parameter #1 ('x')`。参数 `x:int` 的 body binding
仍保留。证据：A:236-264。

另一例把函数体写为 `return x`、调用写为 `f(1)` 时，调用实际 RESOLVED，返回类型 Variant。**不能声称损坏默认值使所有调用失败，也不能将合法传参调用的
discarded-result warning 当成同根因双报。** 证据：D:112-142。

根因在 `GdScriptParser.java:2138-2152`：解析默认表达式失败后仍构造普通 Parameter，没有“默认值正在编辑/损坏”的状态。现有默认值岛只能跳过存在的表达式，无法从
null 区分是否有源级默认值。

建议保留参数名、已识别类型与 body binding，但把 arity/default 完整性标成不可信；不要把坏默认值当作不存在的默认值，也不要通过跳过整函数来修复。

### 3.2 必填参数在 optional 参数后被删除

```gdscript
func f(a: int = 1, b: int) -> int:
	return b

func use():
	f(1)
```

新 parser 报非法参数顺序，但 `GdScriptParser.java:2320-2322` 直接 `continue`，参数列表只剩 `a`。下游实际为：

- 注册签名 `f(a:int=default)->int`，没有 `b`。
- body 的 `b` 丢失参数 binding，追加 binding 与 expression-resolution 错误。
- `f(1)` 对截短签名为 RESOLVED，返回 int。

证据：D:144-176。错误输入已有 parse ERROR，未编译放行；问题在编辑器语义把截短签名作为普通完整签名消费。不能用 RESOLVED 状态证明所有
route 都有 `ExactCallableBoundary`，本例日志 boundary 为 `-`。

建议将“签名顺序非法”与“参数不存在”分开，保留参数 identity/位置供 body 与查询使用，不允许删除参数后无标记地进行可信
overload/arity 选择。

## 4. 损坏注解被截短，并投影为正常属性元数据

### 4.1 删除坏实参后仍满足参数数量

```gdscript
@export_range(0, 1+, 2)
var hp: float = 1.0

@export_enum("A", ,"B")
var he: int = 0

@export_range(1, =3)
var hr: float = 1.0
```

实际结果：

| 属性 | Parser 幸存实参        | GDCC 属性元数据                            |
|----|--------------------|---------------------------------------|
| hp | `[0, 2]`，删除 `1+`   | `annotations={export_range=0,2}`      |
| he | `["A", "B"]`，删除空实参 | `annotations={export_enum=A,B}`       |
| hr | `[1]`              | 不投影 export_range，另发 annotation 参数数量错误 |

全脚本有 5 条 parse ERROR、1 条 `sema.annotation_usage`；hp/he 不追加 sema 错误，却仍获得正常外观的 export 元数据。证据：A:
266-298；单独 hp/he 复现为 D:200-244。

这是比“错误被诊断两次”更具体的发布问题：`@export_range(0, 1+, 2)` 不应在失去损坏身份后被当作真实写出的
`@export_range(0, 2)`。宿主 property 本身健康，可以保留；坏注解自己的元数据投影应停止。

### 4.2 截短后不满足参数数量

单独 `@export_range(1, =3)` 有两个 parser 错误，随后 sema
又报“至少两个参数，实际一个”。这是删除坏实参产生的派生诊断，不是用户独立写了合法的一参数注解。证据：D:178-198。

`GdScriptParser.java:1797-1825` 只追加成功表达式、丢弃坏实参，且注解仍为普通 `AnnotationStatement`。现行
`FrontendAnnotationUsageAnalyzer` 和 skeleton 投影依赖恢复身份；仅按恢复后的 arity 校验不够。

原 `defect-examples-pure07.log` 的旧驱动未打印 annotations；其中 `properties=[hp:float]` **不是**
“未投影”的证据。本报告使用刷新后的驱动输出，不据旧打印缺项推断行为差异。

## 5. 损坏属性在 skeleton 阶段仍被注册

```gdscript
var hp = 1+
var mp = 2
```

新 parser 保留普通 `VariableDeclaration(hp, value=null)`；健康 `mp` 及字面量 2 保留。GDCC registry 实际包含 `hp:Variant` 和
`mp:Variant`，坏 hp 没有因失去初始化器而退出发布。证据：D:73-91。

另一个 EOF 片段：

```gdscript
var hp = (1
```

Parser 报缺 `)`，但保留 `value=LiteralExpression(1)`，hp 仍注册，还追加现有不推断 property 类型的 `sema.type_hint`。证据：D:
93-110。

不能把 `value == null` 一律当坏声明，因为合法无初始化器声明也用 null。需要知道源码是否有初始化器、是否受损。

GDCC 先 skeleton 后 `FrontendErrorSubtreeAnnotator`；仅后置补 skipped 标记不能保证撤销提前注册的 property。恢复状态必须在
skeleton 填成员之前可消费，同时继续保留健康 mp。相关合同：`frontend_rules.md:12-20`、
`frontend_lsp_foundation_implementation.md:116-159`。

## 6. 字典恢复占位 value 超出父节点范围

```gdscript
var d={"a":};
```

实际 AST 的字节范围为：

```text
DictionaryExpression [6,12)
  DictEntry [7,10)
    key:   LiteralExpression [7,10)
    value: LiteralExpression [11,11), kind=null, sourceText=""
```

Parser 有缺 value 的 ERROR，strict 失败；这里不是漏诊断。问题是直接子节点的起点 11 超过父节点的终点 10。

`GdScriptParser.java:2779-2781` 用 key 的末尾结束 entry，而 `nullPlaceholder():2795-2796` 用 current token（此例为 `}`）锚定
value。该 value 并非源中写出的 `null`，但其普通 LiteralExpression 类型无法表达 recovery 身份。

范围包络应按 `parent.start <= child.start && child.end <= parent.end` 检查；零宽子节点恰好位于 parent.end 可以成立。*
*不能把所有零宽节点无法被半开 `nodeAt` 选中本身当成 bug**，也不能把缺 key 的 `var d={:1};`
同列为越界，该例子节点仍在父范围内。证据：S:172-216。

参考 Godot 也插 dummy literal，但 vendor recovery allocator 不给 dummy 普通物理 source extent；Java 将“无源码跨度”的占位映射到当前
token 是额外适配选择。参考位置：`gdscript_parser.cpp:3335-3346`、`gdscript_parser.h:355-359,1525-1533`。

建议在声明清晰的合成节点范围政策下，至少保证输出父子包络自洽，并单独表达 recovery 身份；本次未测得由此引起的崩溃。

完整 GDCC 局部声明 `var d = {"k":}` 还会保留 `LOCAL:d:Variant`；损坏数组 `[1,,3]` 删除中间元素，保留 `[1,3]` 和
local。两例未打印相关容器 expression fact，不能声称已得到精确容器类型或错误运行值。证据：D:341-388。

## 7. 错误后缀被重绑定到前一个有效属性

```gdscript
class_name Rebind07 extends RefCounted

func b(x: int) -> int:
	return x

func f() -> int:
	return self.b.(1)
```

Parser 发一条缺 identifier ERROR，但实际留下 `AttributeExpression(self, [AttributeCallStep("b", [1])])`；GDCC 对整个属性表达式发布
`RESOLVED:int`，sema 为 0。`self.b.(1)` 的坏点号失去独立受损步骤身份。证据：A:170-203。

对应下标片段：

```gdscript
class_name RebindP07 extends RefCounted

var p: Array = [1]

func f() -> Variant:
	return self.p.[0]
```

它保留 `AttributeSubscriptStep("p", [0])`，表达式为 `RESOLVED:Variant`，同样 parse 1 / sema 0。证据：A:205-234。

`GdScriptParser.java:3131-3156,3177-3181` 明确在缺名点号后继续，并将下一调用/下标折到最后一个 property step。现有 parser
测试也固定了这个恢复选择，不是本报告推测的随机行为。

参考 vendor 的失败 `parse_attribute` 仍保留缺属性的 SubscriptNode，后续调用不会直接折到有效名字 b；它与 Java
当前折叠结果不同。但非法源码不在 gdparser 的合法 AST parity 承诺内，不能只因与 vendor 形状不同就声称违背全部 parity 契约。

准确边界：

- 两例都有 parse ERROR，strict/编译错误门禁仍有效。
- 日志 `RESOLVED_CALLS (0)`；已证实的是 AttributeExpression 的类型事实，不是顶层 CallExpression 或已发布 exact callable
  boundary。
- `a..(1)` 的 `..` 是单独 token，不能当成两个独立点并声称被折成 `a(1)`。证据：S:291-311。
- 合法 `a.b(1)`、`a.b[0]` 零诊断，作为正向控制。证据：S:313-364。

建议保留健康 receiver/property 前缀，但不能在受损后缀与真实正常调用之间丢失可信度区别。阻断或标注受损后缀的完整事实，不要求把整个链的健康前缀跳掉。

## 8. `super` 编辑前缀丢失，成员补全没有 receiver

```gdscript
class_name SuperNode07
extends Node

func probe() -> void:
	super.

func healthy() -> int:
	return 1
```

新 AST 中 probe 的 Block 为空，`super` receiver 不存在；healthy 完整保留。旧补全分类器返回 `MEMBER_ACCESS`，但快照无
receiver 事实，候选数为 0。Parser 有两条 ERROR；不能称为整单元失败或下一函数被吞。证据：D:2-34。

继承 GDCC 基类并输入 `super.ba` 时也是空 body 与 0 候选，即使基类已声明 `base_method`。证据：D:36-71。

`GdScriptParser.java:2907-2938` 在缺方法名、缺 `(` 或参数失败时返回 null。Godot 常规 parser 也能丢弃节点，但编辑器另外保留
`make_completion_context(COMPLETION_SUPER_METHOD, ...)` 等信息；只移植普通 AST 恢复而裁掉这些 hook，不能获得等价编辑器能力。

建议保留可识别 super 前缀，或独立发布同代范围级光标上下文；候选仍只枚举词法超类的方法，不按 super
表达式的当前类类型枚举。该回归已由迁移测试支持，不是要求普通编译 parser 接受完整 `super.ba` 语句。

## 9. 恢复后的派生诊断与新增检查双报

### 9.1 损坏带值 return 变裸 return

下面两个函数应分别解析：

```gdscript
func f() -> int:
	return =3
```

```gdscript
func f() -> int:
	return 1+
```

Parser 已报原始语法错误，却留下普通 `ReturnStatement(value=null)`。GDCC 再报 `sema.type_check`：“int 返回槽不能裸
return”。这是恢复产生的假裸返回所触发的派生错误，不是独立的类型错误。证据：D:246-288。

### 9.2 Parser 与 sema 同时检查 loop control

```gdscript
func b():
	break

func c():
	continue
```

break/continue 各有 `parse.lowering` 与 `sema.loop_control_flow` 一条，分别锚相同的 `[11,16)` 与 `[29,37)`。这是诊断 owner
双报，不是重复 class owner 或重复函数注册。证据：D:290-313。

### 9.3 同一嵌套类型限制在不同范围重复报告

```gdscript
func f(v: Variant):
	var a = v as Array[Array[int]]
```

Parser 的 nested-collection ERROR 为 `[49,50)`，sema 的 unsupported cast target 为 `[29,51)`
；同一类型限制在两个阶段重复诊断。证据：D:315-339。

建议明确各根因的 owner，用结构化关联保留失败状态并抑制下游同根因报错。不要只按范围相等或英文消息前缀去重；nested cast
的范围并不相等。GDCC analyzer 的独立手工 AST 合同仍需保留，不能直接删掉所有 sema 校验。

## 10. 未完成调用仍不足以支持参数提示

共同脚本前缀为：

```gdscript
func target(x: int) -> int:
	return x

func use():
	var v = target(
```

EOF 最后一行分别改为 `target(`、`target(1,`、`target(1`，均无最终换行：

| 输入尾部        | 新 AST                              | 旧补全上下文        | GDCC 调用事实                       |
|-------------|------------------------------------|---------------|---------------------------------|
| `target(`   | 没有 CallExpression，initializer=null | CALL_ARGUMENT | 无                               |
| `target(1,` | 没有 CallExpression，initializer=null | CALL_ARGUMENT | 无                               |
| `target(1`  | CallExpression(args=[1])           | UNKNOWN       | RESOLVED:int，但缺 `)` 的 ERROR 仍存在 |

证据：D:390-479。这是信息缺口，不是“所有未完成调用都消失”。完整 signature help 仍需 callee/call range、active argument
与候选签名查询；不能以现有成员候选数 0 作为参数提示功能的成功或失败测试，因为接口本来就未提供 signature DTO。

## 11. 不列为新缺陷的变化与边界

- 数字符号不吸收已经接受，不建议修回 Godot 吸收行为。
- `var d={(a)};` 新入口报缺 separator/value，旧入口只警告且 strict
  成功；这是加强错误识别的控制例，不是新入口拒绝合法字典。证据：S:218-239。
- 无 `extends` 的 `class Inner07:` 合法，新入口零诊断且默认 RefCounted；研究 case 名 `nested_class_missing_extends_body`
  不代表存在缺陷。证据：A:141-168。
- 缺 class_name 名称、缺 extends target 均有 ERROR；注册文件派生类名或恢复为 RefCounted
  需要可信度审计，但没有零诊断放行。证据：A:93-139。
- 损坏 `match var :` 的空名绑定不再存活，健康 y 仍绑定；不能因为坏节点未进入 skipped 表就一律认为恢复失败。证据：D:509-535。
- 普通表达式中的 `[1,2,..]` / `{"x":1,..}` 被拒绝，与真实 match 开放 pattern 被支持不是同一问题。
- key-only 字典 match 丢 key、注释归属差异、病态深度预算加 continuation 的作用域问题已有上游登记；本轮没有新增独立运行证据，不重复包装为本次新发现。

## 12. 验证结果与建议验收

### 12.1 本轮实际执行

三个既有研究驱动重编译成功：D 的 20 例、A 的 10 例和 S 的 15 例均执行完毕，退出码为 0。案例之间有交叉，不是 45 个互不重复的标准测试。

S 打印 `CHECKS total=27 passed=27 failed=0`，但这些是验证观察结果的研究断言，不是 parser 正确性验收。尤其字典缺 value
的父子越界被驱动计为 `exempt=1`；范围断言成功不能掩盖该越界。原日志与刷新日志逐字相同。

刷新 D/A 只增加参数 binding、super/signal 和 property annotations 的打印信息；原先 parse/sema 计数、签名、range 与 call
结果未改变。原日志保留，没有修改既有测试来接受新的错误行为。

复现入口在 `tmp/probes/gdparser07-integration/src/gd/script/gdcc/api/analysis/`：

- `Pure07DefectExamplesProbe.java`。
- `Pure07AdditionalDefectProbe.java`。
- `Pure07SyntaxAndRangeProbe.java`。

使用既有隔离编译产物时，可分别运行：

```bash
source tmp/probes/gdparser07-integration/env.sh
java --enable-native-access=ALL-UNNAMED $JVM_NATIVE_FLAGS -cp "$CP" \
  gd.script.gdcc.api.analysis.Pure07DefectExamplesProbe
java --enable-native-access=ALL-UNNAMED $JVM_NATIVE_FLAGS -cp "$CP" \
  gd.script.gdcc.api.analysis.Pure07AdditionalDefectProbe
java --enable-native-access=ALL-UNNAMED $JVM_NATIVE_FLAGS -cp "$CP" \
  gd.script.gdcc.api.analysis.Pure07SyntaxAndRangeProbe
```

### 12.2 后续正式修复应验收什么

1. 先明确 `:` 后缺类型的语言接受/诊断策略，不能把 vendor 源码推断当完整 Godot 运行结论。
2. 保留参数位置、默认值损坏状态和 body binding；受损签名不冒充完整可靠签名。
3. 坏注解自己的投影停止，健康宿主 property 保留；坏 property 在 skeleton 填成员前被识别。
4. 保证恢复节点范围自洽，区分合成值与真实源码 literal；不要新增范围审计豁免来掩盖问题。
5. 受损成员后缀保留恢复身份，健康 receiver 可分析；super 的词法超类候选可查询。
6. 原始 parser 诊断保持 owner，下游只保留失败/恢复状态；健康兄弟与其他文件继续分析。
7. 最终用实际 0.7.x 工件及仓库 targeted wrapper 验证 frontend/lowering；含 parse ERROR 的脚本不得进入 lowering。

本轮没有实施上述修复，也没有运行正式依赖升级、Godot 进程、完整 lowering/backend 或 runtime suite。已有 ERROR
的示例只证明编辑器恢复和发布合同问题，不能据其 RESOLVED 事实声称产生了错误二进制；零诊断参数例则须单独审计，不能靠 ERROR
门禁代替诊断。
