import org.teavm.gradle.api.JSModuleType
import org.teavm.gradle.tasks.GenerateJavaScriptTask

plugins {
    java
    id("org.teavm") version "0.17.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    // The browser bridge uses the modular JVM library on the classpath.
    modularity.inferModulePath.set(false)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    implementation(project(":"))
    implementation(teavm.libs.jso)

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.microsoft.playwright:playwright:1.59.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform { excludeTags("browser") }
}

val explorerParityFixturesFile = layout.buildDirectory.file("parity/fixtures.json")

tasks.register<JavaExec>("explorerParityFixtures") {
    group = "verification"
    description = "Writes the JVM reference inputs and results for the browser parity gate."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.superice.gdparser.explorer.ParityFixtures")
    args(explorerParityFixturesFile.get().asFile.absolutePath)
    outputs.file(explorerParityFixturesFile)
}

teavm {
    js {
        mainClass.set("dev.superice.gdparser.explorer.ExplorerBridge")
        moduleType.set(JSModuleType.ES2015)
        targetFileName.set("gdparser.js")
    }
}

val generateJavaScript = tasks.named<GenerateJavaScriptTask>("generateJavaScript")
val explorerSite = layout.buildDirectory.dir("site")

val stageExplorer by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("src/main/web"))
    from(generateJavaScript.flatMap { it.outputDir })
    into(explorerSite)
}

tasks.assemble {
    dependsOn(stageExplorer)
}

tasks.register<Test>("browserTest") {
    group = "verification"
    description = "Runs the Playwright Chromium tests against the staged explorer."
    dependsOn(stageExplorer, tasks.named("explorerParityFixtures"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("browser") }
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    systemProperty("gdparser.site", explorerSite.get().asFile.absolutePath)
    systemProperty("gdparser.fixtures", explorerParityFixturesFile.get().asFile.absolutePath)
    inputs.dir(explorerSite)
    inputs.file(explorerParityFixturesFile)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("installExplorerChromium") {
    group = "verification"
    description = "Installs the Chromium release used by the explorer browser tests."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}
