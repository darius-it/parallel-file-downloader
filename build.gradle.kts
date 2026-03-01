plugins {
    kotlin("jvm") version "2.2.20"
}

group = "me.dariusit"
version = "1.0-SNAPSHOT"


repositories {
    mavenCentral()
}

val ktorVersion: String by project
dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:${ktorVersion}")
    implementation("io.ktor:ktor-client-cio:${ktorVersion}")
    testImplementation("io.ktor:ktor-client-mock:${ktorVersion}")
    implementation("org.slf4j:slf4j-simple:2.0.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

}

tasks.test {
    useJUnitPlatform()
}