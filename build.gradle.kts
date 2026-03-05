plugins {
    id("java")
    id("org.gradlex.extra-java-module-info") version "1.9"
}

group = "dev.superice"
version = "0.2.1"

fun normalizedOsName(name: String): String = when {
    name.contains("win", ignoreCase = true) -> "windows"
    name.contains("mac", ignoreCase = true) -> "macos"
    name.contains("nux", ignoreCase = true) || name.contains("nix", ignoreCase = true) -> "linux"
    else -> "unknown"
}

fun normalizedArchName(name: String): String = when (name.lowercase()) {
    "x86_64", "amd64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> name.lowercase()
}

val detectedOs = normalizedOsName(System.getProperty("os.name"))
val detectedArch = normalizedArchName(System.getProperty("os.arch"))
val defaultNativeResourceDir = layout.projectDirectory.dir("native").asFile.absolutePath
val gdscriptNativeResourceDir = providers.gradleProperty("gdscriptNativeResourceDir").orElse(defaultNativeResourceDir)
val defaultNativeLibDir = layout.projectDirectory.dir("native/$detectedOs-$detectedArch").asFile.absolutePath
val gdscriptNativeLibDir = providers.gradleProperty("gdscriptNativeLibDir").orElse(defaultNativeLibDir)
val treeSitterNgVersion = "0.26.3"
val jetbrainsAnnotationsVersion = "26.0.2"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        resources {
            srcDirs("src/main/resources", "native")
        }
    }
}

dependencies {
    implementation("io.github.bonede:tree-sitter:$treeSitterNgVersion")
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
    testCompileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

extraJavaModuleInfo {
    automaticModule("tree-sitter-0.26.3.jar", "tree.sitter")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    val resourceDir = gdscriptNativeResourceDir.get()
    val nativeLibDir = gdscriptNativeLibDir.get()
    systemProperty("gdparser.gdscript.resourceDir", resourceDir)
    systemProperty("gdparser.gdscript.nativeLibDir", nativeLibDir)
    jvmArgs("-Djava.library.path=$nativeLibDir")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    val resourceDir = gdscriptNativeResourceDir.get()
    val nativeLibDir = gdscriptNativeLibDir.get()
    systemProperty("gdparser.gdscript.resourceDir", resourceDir)
    systemProperty("gdparser.gdscript.nativeLibDir", nativeLibDir)
    jvmArgs("-Djava.library.path=$nativeLibDir")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
