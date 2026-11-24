# GDScript 浏览器解析查看器实施计划

## 阶段状态

| 阶段 | 状态和验收 |
| --- | --- |
| G0：脚手架 | 历史已验证。子项目构建、项目依赖和浏览器固定值导出见 §7；未据此判定真实解析门禁通过。 |
| G1：真实解析门禁 | 通过。正式桥接、定向测试和修订浏览器门禁已验证：20 例全等，4 例命中已知差异，0 个意外失败；A/D 复核结束。 |
| G2：页面与交互 | 通过。正式页面和 Worker 的 Node 定向测试、实际静态产物的 Playwright Java 场景均通过；A/D 均已批准。 |
| G3：性能和最终验收 | 通过。已实测验证冷启动、32,768 UTF-16 单元的输入边界、展示预算、联网及受限网络；A/D 复核、限定范围的 IDE 检查和最后一轮定向重跑均完成。 |

## 1. 首版范围

做一个静态页面：左栏用原生 `<textarea>` 输入 GDScript，右栏在输入停止后自动刷新，显示诊断和只读 AST 缩进树。应用在浏览器内解析，不上传也不执行源码，不请求云端拼写检查，也不启动 Godot 或使用旧原生解析器。此承诺限于应用本身，不涵盖用户的输入法和浏览器扩展。首版不引入前端框架、编辑器组件或应用服务器；不做高亮、补全、执行、诊断跳转和完整 AST 导出。

本计划同时记录实施和验证状态。先验证原有解析器能否经 TeaVM 编译并在浏览器中正确运行，再做页面；如果需要重构解析器、改变字节码目标或重组构建，在门禁处停止并确认。

## 2. 现有解析入口和结果契约

- 入口为 `GdScriptParserFacade.parse(String)`。Facade 无状态，每次解析新建 `GdScriptParser`，从当前全文经过 Java tokenizer 和容错 parser 得到 `AstMappingResult`，其中 `ast()` 是 `SourceFile`，`diagnostics()` 是对应的诊断列表。每次刷新只解析一次；诊断和树来自同一次输入快照。不要调用 `parseStrict`、独立 `tokenize` 或补全入口。`ErrorExpression` 仍引用兼容类型 `frontend.cst.CstIssueKind`，这是随根库交付、无原生依赖的纯枚举。
- 右栏展示诊断严重程度、消息、可空的 `code` 及范围。范围沿用现有契约：`[startByte, endByte)` 为 UTF-8 字节范围，行号和行内字节列零基，缺失位置允许零宽。不要拿字节偏移切 JavaScript 字符串。浏览器可能将 `<textarea>.value` 的换行统一为 LF；右栏范围只对应实际送入解析器的字符串，真正的 CRLF 和孤立代理项要通过桥接入口直接验证。
- `Node.getChildren()` 提供源码顺序的直接子节点，不包含标量字段。首版只遍历这条边，用显式、稳定的标签呈现节点类型，并直接读取少量标量，例如 `FunctionDeclaration.name()`、`VariableDeclaration.name()`、`LiteralExpression.kind()` 和 `sourceText()`。未单独命名的节点仍遍历并以通用标签显示。不要依赖优化构建保留 `getSimpleName()`，也不要为展示调用 `AstSexprSerializer`：其 `pretty` 路径同样依赖 record 和访问器反射。此视图用于观察解析结构，不是完整 AST 序列化。
- 不将 `RecoveryLink` 放进展示结果。它关联本次 AST 的对象身份，不跨重解析保留，也不能作为树边递归遍历。语法错误应得到部分 AST 和诊断；工具链、脚本加载或解析中的内部异常应显示为运行故障，不应伪装成语法诊断。

## 3. 先验证真实解析链路

根 `settings.gradle.kts` 已注册 `:explorer-web`。当前授权覆盖正式解析器桥接和 Web 子项目构建配置，不包括进一步修改根构建配置。本阶段只做门禁验证和正式桥接入口；G1 通过之前不开发完整界面。若门禁要求改变根构建配置，先停线确认。

1. 在根 `settings.gradle.kts` 写 `include(":explorer-web")`，把 `explorer-web/` 纳入同一个 Gradle 多项目构建。子项目只保留自己的 `build.gradle.kts`，不另设 `settings.gradle.kts`；从仓库根目录用 `./gradlew :explorer-web:<任务>` 执行。继续使用 Gradle Wrapper `9.2.0`，将 Java toolchain 和编译目标固定为 `25`。在子项目的 Kotlin DSL `plugins` 块先应用 `java`，再应用 `id("org.teavm") version "0.17.0"`，因为 TeaVM 应用时使用 Java 插件创建的配置。用 Gradle 默认的 Plugin Portal 解析插件，子项目依赖用 `mavenCentral()`。仅对子项目应用 TeaVM；运行根任务时，Gradle 仍可能配置子项目并解析 TeaVM 插件，但明确指定 `:classes` 或 `:test` 不应把浏览器 JavaScript 生成排入任务图。通过 Gradle/JDK 信息和 `javap -verbose` 核对探针实际用到的解析器 class 文件版本（预期 major `69`）。本机已有解析器 class 文件观察到 major `69`；官方支持 Java 25 不等于此仓库的完整链路已通过。
2. 子项目只从自己的 `src/main/java` 编译桥接，通过 `implementation(project(":"))` 使用 0.8.0 清理后的纯 Java 根库，不再纳入根项目源码。桥接依赖 `teavm.libs.jso` 并使用类路径，不新增 JPMS 模块。TeaVM 从运行时类路径读取根库 JAR，Web JAR 不重复打包解析器类。根库 JAR 仍包含 JVM 模块描述符和反射序列化类；桥接不调用反射序列化。核对 TeaVM 类路径不含测试夹具、旧 Tree-sitter 前端或原生库。
3. 在 TeaVM `js` 配置中设置 `mainClass.set("dev.superice.gdparser.explorer.ExplorerBridge")`、`moduleType.set(JSModuleType.ES2015)` 和 `targetFileName.set("gdparser.js")`，其中 `JSModuleType` 来自 `org.teavm.gradle.api`。使用静态 `@JSExport` 导出 `parseForExplorer`。`mainClass` 必填，但 [0.17.0 的入口处理](https://github.com/konsoletyper/teavm/blob/0.17.0/core/src/main/java/org/teavm/vm/TeaVM.java#L360-L402)和 [JavaScript 发射逻辑](https://github.com/konsoletyper/teavm/blob/0.17.0/core/src/main/java/org/teavm/backend/javascript/JavaScriptTarget.java#L518-L565)只在有 `main` 方法时链接与导出它；不必加空 `main`，也不用打包器。先用最小静态验证页和模块 Worker 从生成物显式 `import` 导出函数，确认 `ready` 后可调用；调试和交付优化产物都走这条路径。一个只返回常量的导出不能作为通过证据：验收必须调用真实桥接函数，从 `GdScriptParserFacade.parse` 一直走到右栏将使用的诊断及缩进树结果。
4. 用同一组输入分别调用 JVM 容错入口和浏览器的生产桥接入口。先与现有测试规定的 AST 形状、诊断和范围核对 JVM 基线。常见 ASCII/CJK 与其余非例外样本严格对照完整回复，包括树文本、诊断顺序及所有字段、树截断标志和诊断省略计数。§7 的四个已知差异单独回归、单独报告，不跳过执行，保留独立 JVM 断言，并核对各自约定的浏览器输出。对于不进入页面数据的内部不变量，例如诊断归属对象身份，继续以 JVM 定向测试为准。验证实际优化产物仍可从 Worker 导入、可运行，并保持这些结果。

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

**已确认的兼容差异：** [TeaVM 0.17.0 的 `TCharacter`](https://github.com/konsoletyper/teavm/blob/0.17.0/classlib/src/main/java/org/teavm/classlib/java/lang/TCharacter.java#L694-L752) 在 `isUnicodeIdentifierStart` 和 `isUnicodeIdentifierPart` 中使用类别判定和 `isIdentifierIgnorable` 回退。按首字符/后续字符顺序，JDK 25 实测 `U+0001` 为 `false/true`、`U+2118`（`Math_Symbol`）为 `true/true`；TeaVM 对应分支为 `true/true` 和 `false/false`。[词法扫描](../src/main/java/dev/superice/gdparser/frontend/gdscript/GdTokenizer.java#L1802-L1810)和[括号恢复](../src/main/java/dev/superice/gdparser/frontend/gdscript/BracketBalanceOracle.java#L580-L585)都调用这些 API；模块 Worker 的正式优化入口复现了两条路径的 AST 和诊断差异（§7）。

**兼容决策与停线条件：** 首版允许 §7 已记录的四个 Unicode 标识符差异，前提是修订门禁按第 4 步验证。JVM 行为不变，不加字符分类兼容层，不因差异拒绝或改写源输入。其他意外差异仍阻塞，记录版本、class 文件、最小复现输入、错误原文和实际调用链，区分导入、桥接、类库或解析器失败。若需要修改解析算法、字节码目标或根构建，或大范围修改 Unicode、词法或坐标处理，先停线确认。不要降低解析深度、改用服务器或旧解析器来绕过门禁。修订门禁已通过实际运行和复核，页面阶段已开始。

后续实施时，从仓库根目录依次执行以下命令核对工具链、解析器 class、实际 TeaVM 类路径和 JavaScript 生成任务。`generateJavaScript` 的默认输出目录是 `explorer-web/build/generated/teavm/js/`；核对其中生成的 `gdparser.js` 和可导入的 `parseForExplorer`，不要只查任务退出码。

```bash
./gradlew :explorer-web:javaToolchains --no-daemon --info --console=plain
./gradlew :explorer-web:clean :explorer-web:classes :jar --no-daemon --info --console=plain
javap -verbose -classpath build/libs/gdparser-0.8.0.jar dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade
./gradlew :explorer-web:dependencies --configuration teavmClasspath --no-daemon --info --console=plain
./gradlew :explorer-web:generateJavaScript --no-daemon --info --console=plain
```

## 4. 固定桥接和静态交付边界

1. 将门禁探针收敛为正式桥接：一个静态导出接受一个字符串，仅调用一次容错入口。以 JSO 创建只包含普通 JavaScript 对象、数组、字符串、数字、布尔值和 `null` 的结果，供 Worker 结构化克隆。Java 记录、类名反射、AST 递归对象和运行时异常不进入成功结果。将诊断位置和树中高价值标记（例如 `MISSING`）的范围显示为现有字节、行及字节列坐标。
2. 迭代地按 `getChildren()` 遍历，常见节点用显式标签，其他节点也保留其位置和子树。对每个节点字段先把反斜杠、CR、LF、Tab 等字符转换成可见转义（例如 `\\`、`\r`、`\n` 和 `\t`），再按转义后的长度计入展示预算。每个节点只占一个物理行，原始结果和坐标不变。控制树深度、节点数和文本长度，不另建浏览器端 AST。
3. 在 Worker 克隆、发送结果之前限制诊断条数和全部展示文本的总量。诊断按解析器原顺序取可完整保留的前缀；`code` 若为空则省略对应文本，不造诊断码。UI 根据 `treeTruncated` 和 `omittedDiagnostics` 分别显示树和诊断的可见省略提示，不把提示写入 `treeText`。不修改解析器的结果或限制解析器生成诊断。
4. 在 `:explorer-web` 子项目中添加 `stageExplorer` 同步任务，从 `generateJavaScript` 的输出及静态 HTML/CSS/JS/Worker 打包到 `explorer-web/build/site/`，并明确让子项目的 `assemble` 依赖该任务。确保从干净构建能生成全部文件；生成文件不手工编辑，不需要前端包管理器，也不应把编译期类路径带入静态站点。

### 正式桥接结果

[`ExplorerBridge.parseForExplorer(source)`](../explorer-web/src/main/java/dev/superice/gdparser/explorer/ExplorerBridge.java) 导出普通 JavaScript 对象 `{ treeText, diagnostics, omittedDiagnostics, treeTruncated }`。诊断条目含 `severity`（枚举名）、`code`（字符串或 `null`）、`message`，以及 `startByte`、`endByte`、`startRow`、`startColumn`、`endRow`、`endColumn` 六个整数。位置直接来自解析结果：UTF-8 半开字节范围，零基行和字节列。诊断保持解析器顺序，不因位置而排序；`omittedDiagnostics` 为未传递的总条数。Java 侧 [`ExplorerProjection`](../explorer-web/src/main/java/dev/superice/gdparser/explorer/ExplorerProjection.java) 是同一投影逻辑，供 JVM 测试直接调用。

树每级缩进两个空格，节点行以 ` [startByte,endByte) (startRow:startColumn..endRow:endColumn)` 结尾。常见行如 `FunctionDeclaration name=…`、`FunctionBody`、`PassStatement`、`LiteralExpression kind=… raw=…`；缺失表达式为 `MISSING kind=expression slot=…`，其中 `slot` 取解析器的节点类型。反斜杠、CR、LF、Tab、其他控制字符、Unicode 行与段分隔符以及孤立代理项以可见转义表示，不改原范围。达到深度预算时省略下一层且可继续展示兄弟节点；达到节点或文本预算时停止，保留完整节点行。这些分支均以 `treeTruncated` 标识省略。

当前投影默认值：根节点深度为 `0`，最大深度 `40`，最多 `1,500` 个树节点、`65,536` 个树文本 UTF-16 代码单元、`200` 条诊断，树文本与诊断文本合计最多 `98,304` 个 UTF-16 代码单元。合计按转义后的树和原样的 `severity`、`code`（非 `null` 时）、`message` 计，不是序列化字节数，也不是输入上限。下一条诊断的文本放不下时即停止，不截断字段，也不跳过该条再尝试后续诊断。

## 5. 实施页面：两栏、实时刷新和错误状态

1. 写同源静态 HTML、CSS 和普通 JavaScript。左栏为带标签的原生 `<textarea spellcheck="false">`，右栏从上到下显示状态、诊断及可滚动的 `<pre>` 缩进树。窄屏可以上下排列，不需要自定义编辑行为。首次打开时主动调度示例，不能等用户第一次输入才解析。
2. 用一个原生专用模块 Worker 加载 TeaVM 生成物，沿用双端门禁验证过的显式 `import` 路径，不使用经典 Worker 的 `importScripts()`。Worker 确认生成物加载完且桥接入口可调用后发送 `{ kind: "ready" }`。请求为 `{ revision, source }`，`revision` 是正的安全整数，`source` 是字符串。成功回复 `{ kind: "result", revision, result }`，其中 `result` 见 §4；请求级技术错误回复 `{ kind: "failure", revision }`，不回传源码或异常细节。
3. 页面一收到输入事件就递增 `revision`，清空旧结果并取消旧定时器、废弃旧待发送项。尾沿防抖为 `175 ms`；计时结束后读取原样的 `textarea.value`，不 `trim()`、不替换 Tab 或换行。保留原生输入法行为，组合输入时只标记旧结果，等组合结束再调度解析。
4. 等 `ready` 后发送。任意时刻只保留一个在途请求和一个可覆盖的最新待发送项，不把每次键入排队。收到在途请求的回复时释放在途槽；只有回复版本等于当前输入版本才更新右栏，然后尝试发送最新且已结束防抖的请求。即使 A 在 B 的防抖窗口内完成，也不能再次显示 A；A 运行时快速输入 B/C，旧待发送项不得积压。
5. 分开呈现等待、解析中、无诊断、语法诊断和运行故障。所有用户可控文字（包括节点名称、字面量和诊断）用 `textContent`，不拼入 HTML。请求级异常只影响对应版本，后续编辑可以再试；Worker 启动超过 `10,000 ms`、加载失败、`error`、`messageerror`、回复格式非法或投递失败时，显示解析不可用并保留编辑区内容，允许刷新重试，不回退到主线程解析。
6. 防抖只控制频率，不限制单次成本。首版输入上限为 `32,768` 个 JavaScript 字符串的 UTF-16 代码单元，已在实际静态页面验证边界（§7）。文案说明单位；超限不发送、不截断源码，保留编辑区并提示缩短。桥接投影预算见 §4，结果省略不是解析失败。

## 6. 实施验收：以生产入口和静态产物为准

自动化浏览器测试由 Gradle/JUnit 调用 Playwright Java 启动 Chromium，不使用独立 Python 浏览器测试文件。使用 JDK 25；若未安装 Playwright 管理的 Chromium，先执行下方安装任务，或设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE` 使用现有 Chromium。`browserTest` 会生成 JVM 参考夹具、暂存静态产物并自行启动 Java 回环 HTTP 服务，不需要手动启动服务。服务只提供文件，不处理源码，不要求 `file://` 可用。

```bash
./gradlew :explorer-web:installExplorerChromium --no-daemon --info --console=plain
script/run-gradle-targeted-tests.sh --gradle-task :explorer-web:test --tests GateJvmTest,ExplorerProjectionTest
script/run-gradle-targeted-tests.sh --gradle-task :explorer-web:browserTest --tests ParityBrowserTest,ExplorerPageBrowserTest
node --test explorer-web/src/test/web/controller.test.mjs explorer-web/src/test/web/worker.test.mjs
```

如需手工预览，从仓库根目录用 JDK 25 的 `jwebserver` 提供静态文件；这不是浏览器测试命令：

```bash
./gradlew :explorer-web:clean :explorer-web:assemble --no-daemon --info --console=plain
jwebserver -b 127.0.0.1 -p 8000 -d "$PWD/explorer-web/build/site"
```

浏览器验收使用实际静态页面和生产 Worker，覆盖下列行为。手工预览地址为 `http://127.0.0.1:8000/`：

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

## 7. 验证记录与依据

### 历史验证（G0）

以下 JVM 回归和 Web 脚手架数据记录于 0.8.0 清理前，不是本次发布的重跑结果。此前实际运行的 JVM 定向回归：

```bash
script/run-gradle-targeted-tests.sh --tests GdScriptParserFacadeTest,SourceTextIndexTest,GdScriptParserRangeInvariantTest,GdScriptParserErrorRecoveryTest,NodeChildrenTest
```

Gradle 退出码 `0`，`:test` 实际执行；JUnit XML 共 309 项，失败、错误、跳过均为 0。另外，本机 JDK `25.0.4` 使用 `--release 25 -proc:none` 在内存独立编译了上述共享源码（91 个源文件、153 个 class），只在编译期使用 JetBrains 注解，运行探针无需第三方包。用仓库现有编译产物另行核对了标识符、孤立代理项、括号恢复、诊断压力和三引号样本。这些检查只验证了 JVM 源码边界和行为。

构建脚手架已验证：根 `projects` 列出 `:explorer-web`，`:explorer-web:clean :explorer-web:assemble` 成功（13 秒，报告 5 个任务）。生成物的固定值导出在 Node 中可调用；浏览器也显示 `Ready: gdparser explorer scaffold`，没有页面错误。根 `:classes --dry-run` 只列出根项目任务；`:explorer-web:dependencies --configuration teavmClasspath` 未见旧 tree-sitter 依赖。IDE 从根重新同步后，Web 子项目 Gradle DSL 的报错从 45 条降至 0。这些结果不代表真实解析器通过 TeaVM 验证；正式解析入口、实际解析页面交互、双端结果对照和性能门禁仍未验证。

改用项目依赖后，Web clean assemble 成功，`teavmClasspath` 包含根项目、不含 Tree-sitter，Web JAR 只包含 `ExplorerBridge.class`，浏览器仍可通过模块 Worker 调用脚手架的 `probe`。另用未打包的探针直接调用根库 `GdScriptParserFacade.parse`，有效和不完整的函数输入在 JVM 和 TeaVM 生成的 JavaScript 中均保留了函数声明，诊断数分别为 0 和 1。正式桥接、Unicode 差分及完整浏览器门禁仍待验证。

### G1 修订门禁（本轮验收）

- **桥接和干净构建。** Web `clean`、`assemble` 和 `explorerParityFixtures` 成功。正式入口及字段见 §4；G1 产物还带有脚手架 `probe`，门禁未调用它。根构建配置和 JVM 解析行为未修改。
- **定向 JVM 测试。** Web JUnit 5.10.0 的 XML 报告共 41 项：[`GateJvmTest`](../explorer-web/src/test/java/dev/superice/gdparser/explorer/GateJvmTest.java) 31 项，核对独立解析预期及完整诊断前缀；[`ExplorerProjectionTest`](../explorer-web/src/test/java/dev/superice/gdparser/explorer/ExplorerProjectionTest.java) 10 项，覆盖子节点顺序、位置、错误标记、单行转义及预算边界。根 `:test` 定向运行 Facade、ErrorRecovery、RangeInvariant、SourceTextIndex、Tokenizer 五个测试类，共 387 项。两套报告均无失败、错误或跳过。
- **实际浏览器（迁移前证据）。** 当时由 Python 和浏览器 CLI 采集结果，Chromium 157 的模块 Worker 导入静态目录中的 `gdparser.js`，通过真实桥接执行 [`GateCases`](../explorer-web/src/test/java/dev/superice/gdparser/explorer/GateCases.java) 的 24 个样本。20 例回复全等，包含 `_sample_total2`、`cost中文_2`、`变量_total2`，以及 CRLF、孤立代理项、深度、压力和长输入样本。4 例各自符合[已知差异夹具](../explorer-web/src/test/web/known-differences.json)，单独统计，不算 JVM 匹配。例外绑定样本名和原始 UTF-16 源码，并逐项核对预录的 JVM 与浏览器完整回复。意外失败为 0，退出码为 `0`。这不是迁移后 Java Playwright 的验收结果。
- **反例和超时。** 故意改动普通样本的 AST、已知差异样本的诊断顺序，或使用同名但不同的源代码，门禁均以非零码失败。真实 Chromium 确认同名异源在筛选前被拒绝，无论是否选中该例均失败。实际浏览器样本停滞及模拟浏览器命令超时均退出失败，并保留已完成样本和当时在运行的样本。
- **复核。** A、D 均已完成复核，G1 通过并进入 G2。

**迁移前产物与历史测量。** TeaVM `0.17.0` 的 `BALANCED` 模式生成 `gdparser.js`，与当时的静态交付目录副本一致，均为 509,125 字节，SHA-256 为 `c2c7b2040e2fc764e44b840b269d8da183ad7d7652af12738146f0099b31f479`。解析器和桥接 class 的 major 均为 `69`；Web JAR 只有 8 个 Explorer 生产 class，不含解析器副本或测试。有效函数样本冷 Worker 就绪为 80.7 ms，首次往返为 42.3 ms。32,768 个 UTF-16 单元的有效／不完整样本，三次请求的最重往返为 164.5／168.2 ms；65,536 单元为 246.8／270.6 ms。185,710 单元的套件样本首次往返为 133.0 ms。诊断压力样本最重往返为 92.2 ms，保留 200 条，略去 1,802 条，回复文本为 11,494 个 UTF-16 单元。往返包含克隆和接收，不含实际页面渲染，不能作为 G3 结论。

### G1 原全等门禁（历史对照）

修订验收前，Chromium 157 从模块 Worker 调用同一优化产物，22 个样本中 18 个与 JVM 全等，其余四例导致门禁停线，重跑结果一致。相同的样本包括普通 ASCII、CJK、孤立代理项、CRLF 和分组／套件深嵌套。

下列四例不一致（`\u0001` 表示实际控制字符；表内诊断严重程度均为 `ERROR`，按返回顺序列出）：

| 原始输入（转义记法） | JVM 基线 | Chromium 中的 TeaVM 结果 |
| --- | --- | --- |
| `var \u0001x = 1\n` | `parse.lexical.error [4, 5)`，变量为 `x`。 | 零诊断，变量名包含首部 `U+0001`，即 `\u0001x`。 |
| `var ℘x = 1\n` | 零诊断，变量为 `℘x`。 | `parse.lexical.error [4, 7)`，变量为 `x`。 |
| `var x℘y = 1\n` | 零诊断，变量为 `x℘y`，范围 `[0, 13)`。 | `parse.lexical.error [5, 8)`、`parse.statement.trailing [8, 13)`，变量为 `x`，范围 `[0, 5)`。 |
| `var x = [1,\n\u0001func after(): pass\n` | `parse.lexical.error [12, 13)`、`parse.missing_closer.array [11, 11)`；变量 `x` 范围 `[0, 11)`，并保留顶层 `after`。 | `parse.missing_separator.comma [17, 17)`、`parse.syntax.unexpected_token [25, 26)`、`parse.missing_closer.array [31, 31)`；变量范围延伸到 `[0, 31)`，没有顶层 `after`。 |

最小复现的词法消息原文为 `Invalid character "\u0001" (U+0001).` 或 `Invalid character "℘" (U+2118).`。最后一例的浏览器消息依次为 `Expected "," between array elements.`、`Unexpected ":" in array elements.`、`Expected closing "]" after array elements.`。

导入、桥接和投影链路可运行，原全等门禁失败是 TeaVM 字符分类引起的解析语义差异，不是浏览器入口缺失或显示格式问题。

**原门禁重跑测量。** 有效函数样本冷 Worker 就绪为 80.5 ms，首次桥接为 39.9 ms、往返 40.4 ms。桥接计入解析和投影，不含克隆。32,768 个 UTF-16 单元的有效／不完整样本，三次请求的最重桥接为 148.9／200.1 ms；65,536 单元为 210.2／263.8 ms。185,710 单元的套件样本首次桥接为 109.7 ms。诊断压力样本最重桥接为 76.9 ms，保留 200 条、略去 1,802 条，回复文本为 11,494 个 UTF-16 单元。

### G2 页面与交互

- **桥接脚手架已清理。** Java 源码移除 `probe`，`parseForExplorer` 是唯一 `@JSExport`。重新生成的交付产物由正式页面与 Worker 调用，G3 记录实测。
- **原生页面、样式和页面适配已落地。** [`index.html`](../explorer-web/src/main/web/index.html)、[`style.css`](../explorer-web/src/main/web/style.css) 和 [`app.js`](../explorer-web/src/main/web/app.js) 提供两栏输入和结果、状态、只读缩进树及独立省略提示；诊断和树通过 `textContent` 渲染，不使用外部组件。IDE 格式化完成，页面文件检查零条问题。用 Node 的临时 DOM、Worker 和计时器替身调用实际 `app.js` 和控制器，三组探针通过：示例、转义树及零条保留时的独立省略提示；组合输入委托、原样源码和零宽坐标；Worker 构造失败时保留示例。探针未提交为测试，不能替代真实静态页面验证。
- **生产 Worker 和刷新控制已验证。** [`controller.js`](../explorer-web/src/main/web/controller.js) 和 [`worker.js`](../explorer-web/src/main/web/worker.js) 已由 IDE 格式化；消息合同见 §5。Node `24.15.0` 定向运行控制器 15 项、Worker 入口 2 项，17 项通过。Node 测试用替身控制时钟和消息，Worker 入口使用假的生成模块。随后通过下列浏览器场景验证生产模块和页面。
- **真实页面。** Chromium `147.0.7727.15` 在静态产物上通过首次示例、清空、受损后修复、实际文本域的换行和坐标、三引号与类 HTML 文本。直接调用生产 Worker 也核对了原样 CRLF 和孤立代理项。真实缺失 `/gdparser.js` 时服务返回 `404`，页面明确显示不可用，编辑区仍可编辑。
- **版本竞争和组合输入。** 测试闸门观测到只发送版本 `2, 3`、只显示 `3`；`4, 6` 只显示 `6`，到期的待发 `5` 被替换；`7, 8` 中旧故障不覆盖等待，显示 `8`；当前版本 `9` 报技术故障，同一 Worker 随后成功显示 `10`。Chromium CDP 验证组合草稿发送 0 次，提交时发送 1 次；未测试操作系统输入法。
- **自动化迁移已验证。** IDE 收尾后，Web JVM 和 Playwright Java 定向重跑均退出 `0`。Web JVM 41 项，Playwright Java 16 项，两轮均无失败、错误或跳过。浏览器测试包括 [`ExplorerPageBrowserTest`](../explorer-web/src/test/java/dev/superice/gdparser/explorer/ExplorerPageBrowserTest.java) 10 项、[`ParityBrowserTest`](../explorer-web/src/test/java/dev/superice/gdparser/explorer/ParityBrowserTest.java) 6 项。Gradle `JavaExec` 通过 Java 25 的包级静态 `main` 重新生成夹具。浏览器门禁重跑 24 个样本，20 例严格全等，4 例单独命中已知差异，意外失败为 0。根定向回归 387 项见 G1。`:explorer-web:test --dry-run` 未调度 `browserTest`、JavaScript 生成、暂存或参考夹具生成。
- **复核。** A、D 均批准 G2。

### G3 冷启动、预算和最终浏览器验收

- **静态交付。** 六个静态资源合计 520,288 字节，其中 `gdparser.js` 为 508,945 字节，SHA-256 为 `bbeb07247d449b4f38adeb3d012d6586914643373ba54cc81786b3d0f371192f`。
- **性能。** 最后一轮用 Chromium `147.0.7727.15` 对三种输入各启动三个新浏览器进程，共九次。冷 Worker 就绪最慢 163.8 ms，首个示例从发送到回复最慢 46.5 ms，从 Worker 创建到首个回复最慢 222.3 ms。随后在同页已解析首个示例的 Worker 上测量 32,768 UTF-16 单元的有效／不完整／树与诊断均高负载样本，输入至估算绘制的最慢值为 351.5／370.7／436.6 ms，Worker 往返最慢为 156.9／172.5／216.5 ms。页面输入监听器最慢 3.5 ms，回复至估算绘制最慢 45.0 ms。「绘制」由结果更新后连续两次 `requestAnimationFrame` 近似，不测实际像素呈现；这些是本机观察结果，不是时延保证。
- **输入和展示边界。** 含补充平面字符且恰好 32,768 单元的输入正常提交；32,769 单元时编辑值完整保留且未发送。组合压力样本显示 65,478 单元树文本，保留 200 条诊断、略去 1,803 条；两种省略提示同时可见，窄屏树仍可滚动。直接给生产 Worker 的诊断压力样本验证克隆前保留 200 条、略去 1,802 条，回复文本 11,494 单元，最慢往返 97.0 ms。
- **网络和受限网络。** 联网测试的浏览器与 HTTP 记录仅有六个同源静态 `GET`，请求体为空，URL、请求体和 `allHeaders()` 均未出现源码的唯一标记。在限制访问范围为本地服务的新浏览器上下文中，冷启动、加载 Worker 和解析均成功；这是浏览器及测试服务观察，不是对操作系统所有流量的断言。
- **测试补强及反例。** 丢弃 Worker 回复的实验在 `200 ms` 时以样本名和版本 `1` 报超时，随后生产 Worker 仍能回复。用真实 CSS 隐藏诊断时，即使 DOM 仍有行，测试也拒绝；用真实 `Cookie` 发送标记时，`allHeaders()` 检查定位并拒绝该请求头。补强只改测试，运行时产物不变。
- **复核与收尾。** A 已批准验收文档，D 已批准三项测试补强，无未决中高优先级问题或验收阻塞。IDE 收尾只检查改动或新增的 Web Java，三份测试文件只作样式清理，未改生产代码；最终定向重跑通过，静态产物大小和哈希未变。树缩进保留兼容 TeaVM 0.17.0 的循环；该版本不支持 IDE 建议的 `StringBuilder.repeat`，不添加检查抑制。

### 参考依据

仓库依据：[架构概览](architecture-overview.md)、[`GdScriptParserFacade`](../src/main/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserFacade.java)、[`SourceTextIndex`](../src/main/java/dev/superice/gdparser/frontend/gdscript/SourceTextIndex.java)、[`Node`](../src/main/java/dev/superice/gdparser/frontend/ast/Node.java)、[`AstSexprSchema`](../src/main/java/dev/superice/gdparser/frontend/serialize/AstSexprSchema.java)、[`GdScriptParserFacadeTest`](../src/test/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserFacadeTest.java) 和 [`GdScriptParserErrorRecoveryTest`](../src/test/java/dev/superice/gdparser/frontend/gdscript/GdScriptParserErrorRecoveryTest.java)。

工具链及浏览器依据：[TeaVM Gradle 插件 0.17.0](https://plugins.gradle.org/plugin/org.teavm/0.17.0)、[TeaVM Gradle 配置文档](https://teavm.org/docs/tooling/gradle.html)、[TeaVM 0.17.0 发布说明](https://teavm.org/docs/release-notes/0.17.0.html)、[TeaVM 对 Java 25 的支持](https://teavm.org/docs/release-notes/0.13.0.html)、[TeaVM JavaScript 模块](https://teavm.org/docs/runtime/js-modules.html)、[TeaVM 的 `java.lang.Class` 支持情况](https://teavm.org/jcl-report/recent/classes/java.lang.Class.html)、[TeaVM Gradle 插件 0.17.0 源码](https://github.com/konsoletyper/teavm/blob/0.17.0/tools/gradle/src/main/java/org/teavm/gradle/TeaVMPlugin.java#L398-L426)、[Gradle 的 Java 版本兼容性](https://docs.gradle.org/9.2.0/userguide/compatibility.html#java_runtime)、[HTML 的 `textarea` 规范](https://html.spec.whatwg.org/multipage/form-elements.html#the-textarea-element) 和 [Web Worker 构造参数](https://developer.mozilla.org/en-US/docs/Web/API/Worker/Worker)。
