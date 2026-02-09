plugins {
    id("java-library")
}

val isMac = System.getProperty("os.name").lowercase().contains("mac")
val jvmArch = System.getProperty("os.arch").lowercase()
val macCmakeArch = when {
    jvmArch.contains("aarch64") || jvmArch.contains("arm64") -> "arm64"
    else -> "x86_64"
}
val macResourceArch = when (macCmakeArch) {
    "x86_64" -> "x64"
    else -> "arm64"
}

dependencies {
    api(project(":mediainterface-core"))
    implementation("org.slf4j:slf4j-api:2.0.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_16
    targetCompatibility = JavaVersion.VERSION_16
}

val nativeMacDir = layout.projectDirectory.dir("src/native/macos")
val nativeMacBuildDir = layout.buildDirectory.dir("native/macos")

val cmakeConfigureMacos = tasks.register<Exec>("cmakeConfigureMacos") {
    onlyIf { isMac }
    commandLine(
        "cmake",
        "-S", nativeMacDir.asFile.absolutePath,
        "-B", nativeMacBuildDir.get().asFile.absolutePath,
        "-DCMAKE_OSX_ARCHITECTURES=$macCmakeArch"
    )
}

val cmakeBuildMacosAdapter = tasks.register<Exec>("cmakeBuildMacosAdapter") {
    onlyIf { isMac }
    dependsOn(cmakeConfigureMacos)
    commandLine(
        "cmake",
        "--build", nativeMacBuildDir.get().asFile.absolutePath,
        "--target", "MediaRemoteAdapter", "MediaRemoteAdapterTestClient",
        "--config", "Release"
    )
}

val stageMacosAdapterFramework = tasks.register<Copy>("stageMacosAdapterFramework") {
    onlyIf { isMac }
    dependsOn(cmakeBuildMacosAdapter)
    into(layout.buildDirectory.dir("tmp/macos-adapter-stage"))
    from(nativeMacBuildDir.map { it.dir("MediaRemoteAdapter.framework") }) {
        into("MediaRemoteAdapter.framework")
    }
    from(nativeMacBuildDir.map { it.dir("Release/MediaRemoteAdapter.framework") }) {
        into("MediaRemoteAdapter.framework")
    }
}

val zipMacosAdapterFramework = tasks.register<Zip>("zipMacosAdapterFramework") {
    onlyIf { isMac }
    dependsOn(stageMacosAdapterFramework)
    archiveFileName.set("MediaRemoteAdapter.framework.zip")
    destinationDirectory.set(layout.buildDirectory.dir("tmp/macos-adapter-zip"))
    from(layout.buildDirectory.dir("tmp/macos-adapter-stage/MediaRemoteAdapter.framework")) {
        into("MediaRemoteAdapter.framework")
    }
}

val copyMacosAdapterAssets = tasks.register<Copy>("copyMacosAdapterAssets") {
    onlyIf { isMac }
    dependsOn(zipMacosAdapterFramework, cmakeBuildMacosAdapter)
    from(zipMacosAdapterFramework.map { it.archiveFile })
    from(nativeMacBuildDir.map { it.file("MediaRemoteAdapterTestClient") })
    from(nativeMacBuildDir.map { it.file("Release/MediaRemoteAdapterTestClient") })
    into(layout.buildDirectory.dir("resources/main/native/macos/adapter/$macResourceArch"))
}

tasks.named("processResources") {
    if (isMac) {
        dependsOn(copyMacosAdapterAssets)
    }
}
