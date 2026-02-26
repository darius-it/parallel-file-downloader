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
    implementation("io.ktor:ktor-client-core:${ktor_version}")
    implementation("io.ktor:ktor-client-cio:${ktor_version}")
}

tasks.test {
    useJUnitPlatform()
}