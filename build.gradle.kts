import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    base
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

    val sha = (System.getenv("GITHUB_SHA") ?: "unknown").take(8)
    val base = System.getenv("SNAPSHOT_BASE_VERSION") ?: "1.0.0"
    return "$base-SNAPSHOT-$sha"
}

allprojects {
    group = "org.endlesssource"
    version = computeCiAwareVersion()
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withId("java") {
        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension>("publishing") {
            val hasMavenJavaPublication = publications.names.contains("mavenJava")
            if (!hasMavenJavaPublication && components.names.contains("java")) {
                publications.create<MavenPublication>("mavenJava") {
                    from(components["java"])
                }
            }

            val repoPath = (findProperty("gpr.repo") as String?) ?: System.getenv("GITHUB_REPOSITORY")
            val user = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
            val key = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            if (!repoPath.isNullOrBlank() && !user.isNullOrBlank() && !key.isNullOrBlank()) {
                repositories.maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/$repoPath")
                    credentials {
                        username = user
                        password = key
                    }
                }
            }
        }

        tasks.withType<PublishToMavenRepository>().configureEach {
            onlyIf {
                val repoPath = (project.findProperty("gpr.repo") as String?) ?: System.getenv("GITHUB_REPOSITORY")
                val user = (project.findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                val key = (project.findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
                !repoPath.isNullOrBlank() && !user.isNullOrBlank() && !key.isNullOrBlank()
            }
        }
    }
}
