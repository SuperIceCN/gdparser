import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("java")
    id("maven-publish")
    id("org.gradlex.extra-java-module-info") version "1.9"
}

val defaultProjectGroup = "dev.superice"
val defaultProjectVersion = "0.5.3"
val requestedGroup = findProperty("group")?.toString()
val requestedVersion = findProperty("version")?.toString()

group = when {
    requestedGroup.isNullOrBlank() -> defaultProjectGroup
    requestedGroup == "unspecified" -> defaultProjectGroup
    else -> requestedGroup
}
version = when {
    requestedVersion.isNullOrBlank() -> defaultProjectVersion
    requestedVersion == "unspecified" -> defaultProjectVersion
    else -> requestedVersion
}

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
    withSourcesJar()
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = rootProject.name

            pom {
                name.set(rootProject.name)
                description.set("A Java 25 GDScript parser pipeline built on Tree-sitter.")
                url.set("https://github.com/SuperIceCN/gdparser")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit/")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/SuperIceCN/gdparser.git")
                    developerConnection.set("scm:git:ssh://git@github.com/SuperIceCN/gdparser.git")
                    url.set("https://github.com/SuperIceCN/gdparser")
                }
            }
        }
    }
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
