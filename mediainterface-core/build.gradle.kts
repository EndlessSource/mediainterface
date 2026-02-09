plugins {
    id("java-library")
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.9")
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_16
    targetCompatibility = JavaVersion.VERSION_16
}
