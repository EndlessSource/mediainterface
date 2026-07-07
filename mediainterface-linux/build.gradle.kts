plugins {
    id("java-library")
}

dependencies {
    api(project(":mediainterface-core"))
    api("com.github.hypfvieh:dbus-java-core:4.3.1")
    api("com.github.hypfvieh:dbus-java-transport-native-unixsocket:4.3.1")
    implementation("org.slf4j:slf4j-api:2.0.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}
