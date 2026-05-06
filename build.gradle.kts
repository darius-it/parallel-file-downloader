plugins {
    kotlin("jvm") version "2.2.20"
}

group = "me.dariusit"
version = "1.0-SNAPSHOT"


repositories {
    mavenCentral()
}

val ktor_version: String by project
dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:${ktor_version}")
    implementation("io.ktor:ktor-client-cio:${ktor_version}")
    testImplementation("io.ktor:ktor-client-mock:${ktor_version}")
    implementation("org.slf4j:slf4j-simple:2.0.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
}

tasks.test {
    useJUnitPlatform()
}