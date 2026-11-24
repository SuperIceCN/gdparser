import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("java")
    id("maven-publish")
}

val defaultProjectGroup = "dev.superice"
val defaultProjectVersion = "0.8.0"
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

dependencies {
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
    testCompileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = rootProject.name

            pom {
                name.set(rootProject.name)
                description.set("A pure Java 25 GDScript parser with a typed AST and diagnostics.")
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
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
