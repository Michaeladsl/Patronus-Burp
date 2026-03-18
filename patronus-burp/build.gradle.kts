plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.patronus"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Montoya API - latest stable
    compileOnly("net.portswigger.burp.extensions:montoya-api:2025.12")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Kotlin stdlib
    implementation(kotlin("stdlib"))
}

tasks.shadowJar {
    archiveFileName.set("patronus-burp.jar")
    archiveClassifier.set("")

    // Exclude Montoya API from fat JAR - Burp provides it at runtime
    dependencies {
        exclude(dependency("net.portswigger.burp.extensions:montoya-api"))
    }

    manifest {
        attributes["Extension-Class"] = "com.patronus.PatronusBurp"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Compile targeting Java 17 bytecode using whatever JDK is on PATH
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
