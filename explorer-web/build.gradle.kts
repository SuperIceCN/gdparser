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
}

teavm {
    js {
        mainClass.set("dev.superice.gdparser.explorer.ExplorerBridge")
        moduleType.set(JSModuleType.ES2015)
        targetFileName.set("gdparser.js")
    }
}

val generateJavaScript = tasks.named<GenerateJavaScriptTask>("generateJavaScript")

val stageExplorer by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("src/main/web"))
    from(generateJavaScript.flatMap { it.outputDir })
    into(layout.buildDirectory.dir("site"))
}

tasks.assemble {
    dependsOn(stageExplorer)
}
