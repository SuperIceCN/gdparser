# GDScript 浏览器解析查看器实施计划

## 1. 首版范围

做一个静态页面：左栏用原生 `<textarea>` 输入 GDScript，右栏在输入停止后自动刷新，显示诊断和只读 AST 缩进树。应用在浏览器内解析，不上传也不执行源码，不请求云端拼写检查，也不启动 Godot 或使用旧原生解析器。此承诺限于应用本身，不涵盖用户的输入法和浏览器扩展。首版不引入前端框架、编辑器组件或应用服务器；不做高亮、补全、执行、诊断跳转和完整 AST 导出。

这份文档只规划后续实施。先验证原有解析器能否经 TeaVM 编译并在浏览器中正确运行，再做页面；如果需要重构解析器、改变字节码目标或重组构建，在门禁处停止并确认。

## 2. 现有解析入口和结果契约

- 入口为 `GdScriptParserFacade.parse(String)`。Facade 无状态，每次解析新建 `GdScriptParser`，从当前全文经过 Java tokenizer 和容错 parser 得到 `AstMappingResult`，其中 `ast()` 是 `SourceFile`，`diagnostics()` 是对应的诊断列表。每次刷新只解析一次；诊断和树来自同一次输入快照。不要调用 `parseStrict`、独立 `tokenize` 或补全入口。`ErrorExpression` 仍引用兼容类型 `frontend.cst.CstIssueKind`，这是随根库交付、无原生依赖的纯枚举。
- 右栏展示诊断严重程度、消息、可空的 `code` 及范围。范围沿用现有契约：`[startByte, endByte)` 为 UTF-8 字节范围，行号和行内字节列零基，缺失位置允许零宽。不要拿字节偏移切 JavaScript 字符串。浏览器可能将 `<textarea>.value` 的换行统一为 LF；右栏范围只对应实际送入解析器的字符串，真正的 CRLF 和孤立代理项要通过桥接入口直接验证。
- `Node.getChildren()` 提供源码顺序的直接子节点，不包含标量字段。首版只遍历这条边，用显式、稳定的标签呈现节点类型，并直接读取少量标量，例如 `FunctionDeclaration.name()`、`VariableDeclaration.name()`、`LiteralExpression.kind()` 和 `sourceText()`。未单独命名的节点仍遍历并以通用标签显示。不要依赖优化构建保留 `getSimpleName()`，也不要为展示调用 `AstSexprSerializer`：其 `pretty` 路径同样依赖 record 和访问器反射。此视图用于观察解析结构，不是完整 AST 序列化。
- 不将 `RecoveryLink` 放进展示结果。它关联本次 AST 的对象身份，不跨重解析保留，也不能作为树边递归遍历。语法错误应得到部分 AST 和诊断；工具链、脚本加载或解析中的内部异常应显示为运行故障，不应伪装成语法诊断。

## 3. 先验证真实解析链路

根 `settings.gradle.kts` 注册 `:explorer-web` 已获授权；库根 `build.gradle.kts` 保持不变。这不等于授权实施正式解析器桥接。获准后，本阶段只做可丢弃的验证页和正式桥接入口，不开发完整界面；若门禁要求进一步修改根构建配置，先确认。

1. 在根 `settings.gradle.kts` 写 `include(":explorer-web")`，把 `explorer-web/` 纳入同一个 Gradle 多项目构建。子项目只保留自己的 `build.gradle.kts`，不另设 `settings.gradle.kts`；从仓库根目录用 `./gradlew :explorer-web:<任务>` 执行。继续使用 Gradle Wrapper `9.2.0`，将 Java toolchain 和编译目标固定为 `25`。在子项目的 Kotlin DSL `plugins` 块先应用 `java`，再应用 `id("org.teavm") version "0.17.0"`，因为 TeaVM 应用时使用 Java 插件创建的配置。用 Gradle 默认的 Plugin Portal 解析插件，子项目依赖用 `mavenCentral()`。仅对子项目应用 TeaVM；运行根任务时，Gradle 仍可能配置子项目并解析 TeaVM 插件，但明确指定 `:classes` 或 `:test` 不应把浏览器 JavaScript 生成排入任务图。通过 Gradle/JDK 信息和 `javap -verbose` 核对探针实际用到的解析器 class 文件版本（预期 major `69`）。本机已有解析器 class 文件观察到 major `69`；官方支持 Java 25 不等于此仓库的完整链路已通过。
2. 子项目只从自己的 `src/main/java` 编译桥接，通过 `implementation(project(":"))` 使用 0.8.0 清理后的纯 Java 根库，不再纳入根项目源码。桥接依赖 `teavm.libs.jso` 并使用类路径，不新增 JPMS 模块。TeaVM 从运行时类路径读取根库 JAR，Web JAR 不重复打包解析器类。根库 JAR 仍包含 JVM 模块描述符和反射序列化类；桥接不调用反射序列化。核对 TeaVM 类路径不含测试夹具、旧 Tree-sitter 前端或原生库。
3. 在 TeaVM `js` 配置中设置 `mainClass.set("dev.superice.gdparser.explorer.ExplorerBridge")`、`moduleType.set(JSModuleType.ES2015)` 和 `targetFileName.set("gdparser.js")`，其中 `JSModuleType` 来自 `org.teavm.gradle.api`。使用静态 `@JSExport` 导出 `parseForExplorer`。`mainClass` 必填，但 [0.17.0 的入口处理](https://github.com/konsoletyper/teavm/blob/0.17.0/core/src/main/java/org/teavm/vm/TeaVM.java#L360-L402)和 [JavaScript 发射逻辑](https://github.com/konsoletyper/teavm/blob/0.17.0/core/src/main/java/org/teavm/backend/javascript/JavaScriptTarget.java#L518-L565)只在有 `main` 方法时链接与导出它；不必加空 `main`，也不用打包器。先用最小静态验证页和模块 Worker 从生成物显式 `import` 导出函数，确认 `ready` 后可调用；调试和交付优化产物都走这条路径。一个只返回常量的导出不能作为通过证据：验收必须调用真实桥接函数，从 `GdScriptParserFacade.parse` 一直走到右栏将使用的诊断及缩进树结果。
4. 用同一组输入分别调用 JVM 容错入口和浏览器的生产桥接入口。先与现有测试规定的 AST 形状、诊断和范围核对 JVM 基线，再逐项比对两端诊断顺序、严重程度、`code`、消息及范围，以及展示树的标签、顺序和代表性节点值。对于不进入页面数据的内部不变量，例如诊断归属对象身份，继续以 JVM 定向测试为准。验证实际优化产物仍可从 Worker 导入、可运行，并保持这些结果。

| 输入（使用转义表示换行等字符） | 独立检查点 |
| --- | --- |
| `func f():\n\tpass\n` | 函数 `f` 和 `pass` 保留，无错误诊断。 |
| `var a = \nvar b = 2\n` | `a` 的初始化有诊断，仍保留 `a` 与 `b` 两个同级声明。 |
| `func f():\r\n\tvar x = "😀中" +` | 照现有用例只有一条错误诊断，缺失右操作数；诊断覆盖 `+`，缺失标记是物理 EOF 的零宽位置，不能混淆两种范围。 |
| `var x = "\uD800" +`，其中 `\uD800` 为一个孤立高代理项 | JVM 的诊断是 `parse.missing_expression.binary_right`，范围 `[12, 13)`；缺失标记为 `[13, 13)`。原样经 Worker 和桥接入口，按现有规则计一个替代字节，不拿浏览器 `TextEncoder` 所得长度替换解析器坐标。 |
| `var \u0001x = 1\n`、`var \u2118x = 1\n`、`var 中x = 1\n` | JVM 实测：首例保留声明 `x`，有一条 `parse.lexical.error`；后两例分别保留 `℘x` 和 `中x`，无诊断。前两个用于捕获 Unicode 分类差异，第三个是正常多字节标识符对照。 |
| `var x = [1,\n\u0001func after(): pass\n` | 覆盖括号恢复对下一行开头的判断。JVM 实测保留顶层变量 `x` 和函数 `after`；诊断依次为 `parse.lexical.error [12, 13)`、`parse.missing_closer.array [11, 11)`。 |
| `var text = """first\n\tfakeChild\nlast"""\nvar other = 1\n` | JVM 实测两个同级声明、零诊断，字面量 `sourceText` 确实包含换行和 Tab。展示中该节点必须仍只占一行，随后声明的缩进不变。 |
| 空串、深嵌套分组/套件、长的多行有效与不完整代码 | 空串也解析。已有深嵌套用例应得到解析深度诊断而不是 JS 栈溢出；长输入用来量实际最重路径。 |

**优先验证的兼容风险：** [TeaVM 0.17.0 的 `TCharacter`](https://github.com/konsoletyper/teavm/blob/0.17.0/classlib/src/main/java/org/teavm/classlib/java/lang/TCharacter.java#L694-L752) 的 `isUnicodeIdentifierStart` 和 `isUnicodeIdentifierPart` 都有 `isIdentifierIgnorable` 回退分支。按首字符/后续字符顺序，JDK 25 实测 `U+0001` 为 `false/true`、`U+2118`（`Math_Symbol`）为 `true/true`；按 TeaVM 已固定版本的源码分支，预计分别为 `true/true` 和 `false/false`，均未在浏览器实测。用上表从生产优化版 Worker 的真实入口验证，不只测 `Character`：[词法扫描](../src/main/java/dev/superice/gdparser/frontend/gdscript/GdTokenizer.java#L1802-L1810)和[括号恢复](../src/main/java/dev/superice/gdparser/frontend/gdscript/BracketBalanceOracle.java#L580-L585)都使用这些判定，两个路径都要维持相同语义。对照代码同时覆盖 record、模式匹配、集合、格式化及 Unicode 路径；类库中存在相应 API 不是通过证明。

**停线条件：** 失败时记录版本、class 文件、最小复现输入、错误原文和实际调用链，指出是 JavaScript 导入、桥接、类库语义还是解析器失败。若证实差分，先确认能否局部保持 JVM 语义；经确认后优先局部修正，并重跑同输入的双端门禁。若需要修改解析算法、字节码目标或调整根构建（超出注册子项目），或需要大范围修改 Unicode、词法或坐标处理，先停线征求确认。例如，若深嵌套在 JVM 产出预期诊断，而 TeaVM 出现 `RangeError`，先用调用栈确定失败发生在解析还是展示。不要降低解析深度或禁止某些 Unicode 输入来掩盖差异。未通过前不做 UI，也不改用服务器或旧解析器来绕过门禁。

后续实施时，从仓库根目录依次执行以下命令核对工具链、解析器 class、实际 TeaVM 类路径和 JavaScript 生成任务。`generateJavaScript` 的默认输出目录是 `explorer-web/build/generated/teavm/js/`；核对其中生成的 `gdparser.js` 和可导入的 `parseForExplorer`，不要只查任务退出码。

```bash
./gradlew :explorer-web:javaToolchains --no-daemon --info --console=plain
./gradlew :explorer-web:clean :explorer-web:classes :jar --no-daemon --info --console=plain
javap -verbose -classpath build/libs/gdparser-0.8.0.jar dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade
./gradlew :explorer-web:dependencies --configuration teavmClasspath --no-daemon --info --console=plain
./gradlew :explorer-web:generateJavaScript --no-daemon --info --console=plain
```

## 4. 固定桥接和静态交付边界

1. 将门禁探针收敛为正式桥接：一个静态导出接受一个字符串，仅调用一次容错入口。以 JSO 创建只包含普通 JavaScript 对象、数组、字符串、数字和 `null` 的结果，供 Worker 结构化克隆。Java 记录、类名反射、AST 递归对象和运行时异常不进入成功结果。只保留 `treeText` 和诊断字段；如达到展示预算，附省略标记。将诊断位置和树中高价值标记（例如 `MISSING`）的范围显示为现有字节、行及字节列坐标。
2. 迭代地按 `getChildren()` 遍历，常见节点用显式标签，其他节点也保留其位置和子树。对每个节点字段先把反斜杠、CR、LF、Tab 等字符转换成可见转义（例如 `\\`、`\r`、`\n` 和 `\t`），再按转义后的长度计入展示预算。每个节点只占一个物理行，原始结果和坐标不变。控制树深度、节点数和文本长度，不另建浏览器端 AST。
3. 在 Worker 克隆、发送结果之前限制诊断条数和全部展示文本的总量。诊断按解析器原顺序取可完整保留的前缀，并告知省略条数；`code` 若为空则省略对应文本，不造诊断码。不修改解析器的结果或限制解析器生成诊断；对未触发限制的小样，仍比较完整的诊断数据。树的省略也要明确提示。
4. 在 `:explorer-web` 子项目中添加 `stageExplorer` 同步任务，从 `generateJavaScript` 的输出及静态 HTML/CSS/JS/Worker 打包到 `explorer-web/build/site/`，并明确让子项目的 `assemble` 依赖该任务。确保从干净构建能生成全部文件；生成文件不手工编辑，不需要前端包管理器，也不应把编译期类路径带入静态站点。

## 5. 实施页面：两栏、实时刷新和错误状态

1. 写同源静态 HTML、CSS 和普通 JavaScript。左栏为带标签的原生 `<textarea spellcheck="false">`，右栏从上到下显示状态、诊断及可滚动的 `<pre>` 缩进树。窄屏可以上下排列，不需要自定义编辑行为。首次打开时主动调度示例，不能等用户第一次输入才解析。
2. 用一个原生专用模块 Worker 加载 TeaVM 生成物，沿用双端门禁验证过的显式 `import` 路径，不使用经典 Worker 的 `importScripts()`。在 Worker 内完成解析和结果整理，不把 Java AST 对象或完整输入快照回传给页面。消息请求为 `{ revision, source }`；回复带同一 `revision`，返回缩进树文本与诊断数据，或返回请求级技术错误。Worker 确认生成物加载完且桥接入口可调用后发送 `ready`。
3. 页面一收到输入事件就递增 `revision`，清空旧结果或明显标记过时，并取消旧定时器、废弃旧待发送项。用约 `150–200 ms` 的尾沿防抖；计时结束后读取原样的 `textarea.value`，不 `trim()`、不替换 Tab 或换行。保留原生输入法行为，组合输入时只标记旧结果，等组合结束再调度解析。
4. 等 `ready` 后发送。任意时刻只保留一个在途请求和一个可覆盖的最新待发送项，不把每次键入排队。收到在途请求的回复时释放在途槽；只有回复版本等于当前输入版本才更新右栏，然后尝试发送最新且已结束防抖的请求。即使 A 在 B 的防抖窗口内完成，也不能再次显示 A；A 运行时快速输入 B/C，旧待发送项不得积压。
5. 分开呈现等待、解析中、无诊断、语法诊断和运行故障。所有用户可控文字（包括节点名称、字面量和诊断）用 `textContent`，不拼入 HTML。请求级异常只影响对应版本，后续编辑可以再试；Worker 启动、加载、`error`、`messageerror` 或投递失败时，显示解析不可用并保留编辑区内容，允许刷新重试，不回退到主线程解析。
6. 防抖只控制频率，不限制单次成本。先用双端门禁样本和长输入测量，再定首版输入上限，以及桥接侧的树深度、节点数、诊断条数和总文本量预算。输入上限按 JavaScript 字符串的 UTF-16 代码单元计数，文案说明单位；超限不发送、不截断源码，保留编辑区并提示缩短。结果省略不是解析失败。预算值和实测数据在首次实现中记录，不在本计划中当作已有性能结果。

## 6. 实施验收：以生产入口和静态产物为准

实现上述任务后，先从干净构建生成页面。用 `localhost` 上的静态 HTTP 服务验证。同源服务只提供文件，不处理源码，不要求 `file://` 可用。

```bash
./gradlew :explorer-web:clean :explorer-web:assemble --no-daemon --info --console=plain
python3 -m http.server --bind 127.0.0.1 --directory explorer-web/build/site 8000
```

打开 `http://127.0.0.1:8000/`，使用真实页面和生产 Worker 分别确认下列行为：

| 场景 | 预期 |
| --- | --- |
| 首次打开有效示例；再清空编辑区 | 示例在 `ready` 后自动解析；空字符串也提交，显示该次解析结果而非上一次的界面。 |
| 有效 → 不完整 → 修复 | 不完整时显示部分树和诊断；修好后一并刷新，不残留旧诊断。 |
| 多字节字符、CRLF、孤立代理项 | 生产桥接端直接传入原样字符串，和 JVM 对照；另用实际 `<textarea>.value` 验证浏览器归一化后的输入与范围。 |
| 原生输入法组合；源码中有形似 HTML 的字面量 | 组合行为不被拦截，完成后解析；`"<b>示例</b>"` 等内容按文字显示，不生成 HTML 元素。 |
| 含真换行和 Tab 的三引号字面量 | 用第 3 节样本检查缩进树：字面量只占一行，控制字符显示为转义文本，后续声明缩进不变，原 AST 范围不变。 |
| 缺失生成脚本或错误入口 | 启动故障明确可见，源码保留，无主线程回退。 |
| 诊断压力样本 | 用 [`ErrorRecoveryTest` 中重复 2,000 个 `§` 的输入](../src/test/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserErrorRecoveryTest.java#L8647-L8664)。JVM 实测有 2,002 条诊断，其中 2,001 条是词法诊断，保留两个顶层语句。直接调用 Worker 绕开展示页的输入保护，验证在克隆前输出保持原顺序的诊断前缀、正确的省略条数，以及诊断条数和文本总量均不超预算。 |
| 最重真实输入、恰好到达和超过输入预算、展示截断 | 从冷启动和首次解析开始记录生成物大小、Worker 就绪及首个结果时间、最重输入的解析和展示时间。验证预算边界分支、超限反馈及编辑区可继续响应。 |
| 联网、关闭缓存并编辑带唯一标记的源码 | 在无扩展浏览器环境查看页面及 Worker 网络记录。只有必要的同源静态请求，不应出现 CDN、应用请求的云端拼写检查、源码标记或源码请求体。 |
| 关闭缓存并阻断外网，只保留本地静态服务 | 从空白页冷启动，页面与 Worker 仍可加载并解析。此项独立验证对外网无依赖。 |

**可重复的版本竞争验收。** 在生产 Worker 的回复边界设断点，或用不随正式产物交付的测试闸门，控制回复释放和一次请求故障。以可控时钟或明确观察到的防抖状态安排事件，不靠快速手敲。每例记录实际发送的版本、在途/待发版本和右栏显示版本：

1. A 在途，输入 B，在 B 仍防抖时释放 A 的成功回复。右栏不显示 A，A 的在途槽释放，B 到期后才发送。
2. 一直扣住 A 的回复，令 B 先到期进入待发槽，再输入 C 并等到期，才释放 A。实际只发送 A、C，最终 AST 和诊断一起更新为 C。
3. A 在途时输入 C，再释放 A 的请求错误。旧错误不能覆盖 C 的等待状态或最终结果；C 正常发送并显示。
4. 对当前版本注入一次请求级技术错误。右栏显示运行故障而非语法诊断；下一次编辑由同一 Worker 成功解析，无需重载页面。

## 7. 当前验证状态与依据

以下 JVM 回归和 Web 脚手架数据记录于 0.8.0 清理前，不是本次发布的重跑结果。此前实际运行的 JVM 定向回归：

```bash
script/run-gradle-targeted-tests.sh --tests GdScriptParserFacadeTest,SourceTextIndexTest,GdScriptParserRangeInvariantTest,GdScriptParserErrorRecoveryTest,NodeChildrenTest
```

Gradle 退出码 `0`，`:test` 实际执行；JUnit XML 共 309 项，失败、错误、跳过均为 0。另外，本机 JDK `25.0.4` 使用 `--release 25 -proc:none` 在内存独立编译了上述共享源码（91 个源文件、153 个 class），只在编译期使用 JetBrains 注解，运行探针无需第三方包。用仓库现有编译产物另行核对了标识符、孤立代理项、括号恢复、诊断压力和三引号样本。这些检查只验证了 JVM 源码边界和行为。

构建脚手架已验证：根 `projects` 列出 `:explorer-web`，`:explorer-web:clean :explorer-web:assemble` 成功（13 秒，报告 5 个任务）。生成物的固定值导出在 Node 中可调用；浏览器也显示 `Ready: gdparser explorer scaffold`，没有页面错误。根 `:classes --dry-run` 只列出根项目任务；`:explorer-web:dependencies --configuration teavmClasspath` 未见旧 tree-sitter 依赖。IDE 从根重新同步后，Web 子项目 Gradle DSL 的报错从 45 条降至 0。这些结果不代表真实解析器通过 TeaVM 验证；正式解析入口、实际解析页面交互、双端结果对照和性能门禁仍未验证。

改用项目依赖后，Web clean assemble 成功，`teavmClasspath` 包含根项目、不含 Tree-sitter，Web JAR 只包含 `ExplorerBridge.class`，浏览器仍可通过模块 Worker 调用脚手架的 `probe`。另用未打包的探针直接调用根库 `GdScriptParserFacade.parse`，有效和不完整的函数输入在 JVM 和 TeaVM 生成的 JavaScript 中均保留了函数声明，诊断数分别为 0 和 1。正式桥接、Unicode 差分及完整浏览器门禁仍待验证。

仓库依据：[架构概览](architecture-overview.md)、[`GdScriptParserFacade`](../src/main/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserFacade.java)、[`SourceTextIndex`](../src/main/java/dev/superice/gdparser/frontend/gdscript/SourceTextIndex.java)、[`Node`](../src/main/java/dev/superice/gdparser/frontend/ast/Node.java)、[`AstSexprSchema`](../src/main/java/dev/superice/gdparser/frontend/serialize/AstSexprSchema.java)、[`GdScriptParserFacadeTest`](../src/test/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserFacadeTest.java) 和 [`GdScriptParserErrorRecoveryTest`](../src/test/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserErrorRecoveryTest.java)。

工具链及浏览器依据：[TeaVM Gradle 插件 0.17.0](https://plugins.gradle.org/plugin/org.teavm/0.17.0)、[TeaVM Gradle 配置文档](https://teavm.org/docs/tooling/gradle.html)、[TeaVM 0.17.0 发布说明](https://teavm.org/docs/release-notes/0.17.0.html)、[TeaVM 对 Java 25 的支持](https://teavm.org/docs/release-notes/0.13.0.html)、[TeaVM JavaScript 模块](https://teavm.org/docs/runtime/js-modules.html)、[TeaVM 的 `java.lang.Class` 支持情况](https://teavm.org/jcl-report/recent/classes/java.lang.Class.html)、[TeaVM Gradle 插件 0.17.0 源码](https://github.com/konsoletyper/teavm/blob/0.17.0/tools/gradle/src/main/java/org/teavm/gradle/TeaVMPlugin.java#L398-L426)、[Gradle 的 Java 版本兼容性](https://docs.gradle.org/9.2.0/userguide/compatibility.html#java_runtime)、[HTML 的 `textarea` 规范](https://html.spec.whatwg.org/multipage/form-elements.html#the-textarea-element) 和 [Web Worker 构造参数](https://developer.mozilla.org/en-US/docs/Web/API/Worker/Worker)。
