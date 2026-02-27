import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.plugins.signing.SigningExtension
import java.util.concurrent.TimeUnit

plugins {
    base
    id("com.gradleup.nmcp.aggregation") version "1.4.4"
    id("com.gradleup.nmcp") version "1.4.4" apply false
}

fun computeCiAwareVersion(): String {
    val isCi = (System.getenv("CI") ?: "").equals("true", ignoreCase = true)
            || (System.getenv("GITHUB_ACTIONS") ?: "").equals("true", ignoreCase = true)
    if (!isCi) {
        return "1.0-SNAPSHOT"
    }

    val refType = System.getenv("GITHUB_REF_TYPE") ?: ""
    val refName = System.getenv("GITHUB_REF_NAME") ?: ""
    if (refType.equals("tag", ignoreCase = true) && refName.isNotBlank()) {
        return refName.removePrefix("v")
    }

    val snapshotBaseVersion = (System.getenv("SNAPSHOT_BASE_VERSION")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: findLatestTagVersion()
        ?: "0.1.0")
    val baseVersion = snapshotBaseVersion
        .removePrefix("v")
        .removeSuffix("-SNAPSHOT")
    return "$baseVersion-SNAPSHOT"
}

fun runGit(vararg args: String): String? {
    return try {
        val command = listOf("git") + args.toList()
        val process = ProcessBuilder(command)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (finished && process.exitValue() == 0 && output.isNotEmpty()) {
            output
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

fun findLatestTagVersion(): String? {
    return runGit("describe", "--tags", "--abbrev=0", "--match", "v*")
        ?: runGit("describe", "--tags", "--abbrev=0")
}

allprojects {
    group = "org.endlesssource.mediainterface"
    version = computeCiAwareVersion()
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

val allPublishableModules = setOf(
    ":mediainterface-core",
    ":mediainterface-linux",
    ":mediainterface-windows",
    ":mediainterface-macos",
    ":mediainterface-all",
    ":examples"
)

val requestedPublishModules = ((findProperty("publish.modules") as String?) ?: "")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { if (it.startsWith(":")) it else ":$it" }
    .toSet()

val unknownPublishModules = requestedPublishModules - allPublishableModules
require(unknownPublishModules.isEmpty()) {
    "Unknown publish.modules entries: ${unknownPublishModules.joinToString(", ")}"
}

val selectedPublishModules = if (requestedPublishModules.isEmpty()) allPublishableModules else requestedPublishModules

nmcpAggregation {
    centralPortal {
        username = (findProperty("sonatype.user") as String?)
            ?: System.getenv("SONATYPE_USER")
            ?: ""
        password = (findProperty("sonatype.pass") as String?)
            ?: System.getenv("SONATYPE_PASS")
            ?: ""
        publishingType = "AUTOMATIC"
    }
}

val isTagRelease = System.getenv("GITHUB_REF_TYPE")?.equals("tag", ignoreCase = true) == true

tasks.register("publishAll") {
    group = "publishing"
    description = "Publish to all configured repositories. Add new destinations here."

    // Maven Central: snapshots on main, releases on tags
    dependsOn(if (isTagRelease) "publishAggregationToCentralPortal" else "publishAggregationToCentralSnapshots")

    // To publish to additional repos, add more dependsOn() calls here, e.g.:
    // dependsOn("publishAllPublicationsToMyOtherRepoRepository")
}

dependencies {
    selectedPublishModules.forEach { modulePath ->
        add("nmcpAggregation", project(modulePath))
    }
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withId("java") {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        apply(plugin = "com.gradleup.nmcp")

        extensions.configure<JavaPluginExtension>("java") {
            withSourcesJar()
            withJavadocJar()
        }

        extensions.configure<PublishingExtension>("publishing") {
            val hasMavenJavaPublication = publications.names.contains("mavenJava")
            if (!hasMavenJavaPublication && components.names.contains("java")) {
                publications.create<MavenPublication>("mavenJava") {
                    from(components["java"])
                }
            }

            publications.withType(MavenPublication::class.java).configureEach {
                artifactId = when {
                    project.name == "mediainterface-core" -> "core"
                    project.name == "mediainterface-linux" -> "linux"
                    project.name == "mediainterface-windows" -> "windows"
                    project.name == "mediainterface-macos" -> "macos"
                    project.name == "mediainterface-all" -> "all"
                    project.name == "examples" -> "examples"
                    project.name.startsWith("mediainterface-") -> project.name.removePrefix("mediainterface-")
                    else -> project.name
                }
                pom {
                    name.set("mediainterface-$artifactId")
                    description.set("Cross-platform Java media-control library for Linux, Windows, and macOS.")
                    url.set(
                        (rootProject.findProperty("pom.url") as String?)
                            ?: "https://github.com/endlesssource/mediainterface"
                    )
                    licenses {
                        license {
                            name.set(
                                (rootProject.findProperty("pom.license.name") as String?)
                                    ?: "Apache License, Version 2.0"
                            )
                            url.set(
                                (rootProject.findProperty("pom.license.url") as String?)
                                    ?: "https://www.apache.org/licenses/LICENSE-2.0.txt"
                            )
                        }
                    }
                    scm {
                        val scmUrl = (rootProject.findProperty("pom.scm.url") as String?)
                            ?: "https://github.com/endlesssource/mediainterface"
                        url.set(scmUrl)
                        connection.set("scm:git:git://github.com/endlesssource/mediainterface.git")
                        developerConnection.set("scm:git:ssh://git@github.com:endlesssource/mediainterface.git")
                    }
                    developers {
                        developer {
                            id.set((rootProject.findProperty("pom.developer.id") as String?) ?: "endlesssource")
                            name.set((rootProject.findProperty("pom.developer.name") as String?) ?: "EndlessSource")
                        }
                    }
                }
            }
        }

        extensions.configure<SigningExtension>("signing") {
            val signingKey = (rootProject.findProperty("signing.key") as String?)
                ?: System.getenv("SIGNING_KEY")
            val signingPassword = (rootProject.findProperty("signing.password") as String?)
                ?: System.getenv("SIGNING_PASSWORD")

            if (!signingKey.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }
}
