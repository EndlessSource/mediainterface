plugins {
    id("java-library")
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")

dependencies {
    api(project(":mediainterface-core"))
    implementation("org.slf4j:slf4j-api:2.0.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_16
    targetCompatibility = JavaVersion.VERSION_16
}

val nativeWindowsDir = layout.projectDirectory.dir("src/native/windows")
val gradleJavaHome = providers.systemProperty("java.home")

fun String.capitalized(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

fun registerWindowsArchBuild(arch: String): TaskProvider<Copy> {
    val suffix = arch.capitalized()
    val cmakeArch = if (arch == "arm64") "ARM64" else "x64"
    val buildDirForArch = layout.buildDirectory.dir("native/windows-$arch")

    val configure = tasks.register<Exec>("cmakeConfigureWindows$suffix") {
        onlyIf { isWindows }
        environment("JAVA_HOME", gradleJavaHome.get())
        val projectDirPath = layout.projectDirectory.asFile.absolutePath
        commandLine(
            "cmd", "/c",
            "pushd \"$projectDirPath\" && " +
                    "cmake -S src\\native\\windows -B build\\native\\windows-$arch -A $cmakeArch && " +
                    "popd"
        )
    }

    val build = tasks.register<Exec>("cmakeBuildWindows$suffix") {
        onlyIf { isWindows }
        dependsOn(configure)
        environment("JAVA_HOME", gradleJavaHome.get())
        val projectDirPath = layout.projectDirectory.asFile.absolutePath
        commandLine(
            "cmd", "/c",
            "pushd \"$projectDirPath\" && " +
                    "cmake --build build\\native\\windows-$arch --config Release && " +
                    "popd"
        )
    }

    return tasks.register<Copy>("copyWindowsDll$suffix") {
        onlyIf { isWindows }
        dependsOn(build)
        from(buildDirForArch.map { it.dir("Release") })
        from(buildDirForArch.map { it.dir("bin/Release") })
        include("*.dll")
        into(layout.buildDirectory.dir("resources/main/native/windows/$arch"))
    }
}

val copyWindowsDllX64 = registerWindowsArchBuild("x64")
val copyWindowsDllArm64 = registerWindowsArchBuild("arm64")

val copyWindowsDlls = tasks.register("copyWindowsDlls") {
    dependsOn(copyWindowsDllX64, copyWindowsDllArm64)
}

tasks.named("processResources") {
    if (isWindows) {
        dependsOn(copyWindowsDlls)
    }
}
