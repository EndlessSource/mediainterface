import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("application")
    id("maven-publish")
    id("com.gradleup.shadow") version "9.0.1"
}

dependencies {
    implementation(project(":mediainterface-all"))
    implementation("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_16
    targetCompatibility = JavaVersion.VERSION_16
}

application {
    mainClass.set("org.endlesssource.mediainterface.examples.SimpleMediaLoggerExample")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dorg.slf4j.simpleLogger.defaultLogLevel=debug")
}

val exampleMainClasses = mapOf(
    "simpleMediaLogger" to "org.endlesssource.mediainterface.examples.SimpleMediaLoggerExample",
    "eventDrivenMedia" to "org.endlesssource.mediainterface.examples.EventDrivenMediaExample",
    "mediaControlCli" to "org.endlesssource.mediainterface.examples.MediaControlCliExample",
    "swingNowPlaying" to "org.endlesssource.mediainterface.examples.SwingNowPlayingExample"
)

val exampleJarTasks = exampleMainClasses.map { (name, mainClassName) ->
    tasks.register<ShadowJar>("${name}ShadowJar") {
        group = "build"
        description = "Build executable shadow jar for $mainClassName"
        archiveBaseName.set("examples")
        archiveClassifier.set(name)
        configurations = listOf(project.configurations.runtimeClasspath.get())
        manifest {
            attributes["Main-Class"] = mainClassName
        }
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        append("META-INF/services/org.endlesssource.mediainterface.spi.PlatformMediaProvider")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        from(sourceSets.main.get().output)
    }
}

tasks.register("exampleJars") {
    group = "build"
    description = "Build all executable example jars."
    dependsOn(exampleJarTasks)
}

tasks.register<JavaExec>("runEventDrivenExample") {
    group = "application"
    description = "Run the event-driven media listener example."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.endlesssource.mediainterface.examples.EventDrivenMediaExample")
}

tasks.register<JavaExec>("runMediaControlCliExample") {
    group = "application"
    description = "Run the interactive media control CLI example."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.endlesssource.mediainterface.examples.MediaControlCliExample")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runSwingGuiExample") {
    group = "application"
    description = "Run the Swing now-playing GUI example."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.endlesssource.mediainterface.examples.SwingNowPlayingExample")
}

publishing {
    publications {
        val publication = (findByName("mavenJava") as? org.gradle.api.publish.maven.MavenPublication)
            ?: create<org.gradle.api.publish.maven.MavenPublication>("mavenJava")
        publication.apply {
            artifact(tasks.named("simpleMediaLoggerShadowJar"))
            artifact(tasks.named("eventDrivenMediaShadowJar"))
            artifact(tasks.named("mediaControlCliShadowJar"))
            artifact(tasks.named("swingNowPlayingShadowJar"))
        }
    }
}
