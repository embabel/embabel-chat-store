// Gradle build for Drivine KSP code generation
//
// Why: Maven's third-party KSP plugin (kotlin-maven-symbol-processing) hasn't been
// updated for Kotlin 2.2.0 yet. This Gradle build is a workaround to generate Drivine DSL code.
//
// How it works:
// 1. This Gradle build runs KSP on the domain classes
// 2. Generated code goes to build/generated/ksp/main/kotlin
// 3. Maven build includes those generated sources via build-helper-maven-plugin
//
// To run code generation: ./gradlew kspKotlin

plugins {
    kotlin("jvm") version "2.2.0"
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
}

group = "com.embabel.chat.store"
version = "0.2.0-SNAPSHOT"

val drivineVersion = "0.0.45"

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://repo.embabel.com/artifactory/libs-snapshot")
    }
    maven {
        url = uri("https://repo.embabel.com/artifactory/libs-release")
    }
}

dependencies {
    // Drivine core library
    implementation("org.drivine:drivine4j:$drivineVersion")

    // KSP processor for code generation
    ksp("org.drivine:drivine4j-codegen:$drivineVersion")

    // Dependencies needed for domain classes to compile
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.7"))
    implementation("com.embabel.agent:embabel-agent-api:0.3.4-SNAPSHOT")
    implementation("com.embabel.common:embabel-common-core:0.1.10-SNAPSHOT")
    implementation("com.fasterxml.uuid:java-uuid-generator:5.2.0")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
}

kotlin {
    compilerOptions {
        // Required for Drivine DSL with context parameters
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }

    // Configure source sets to read from parent project
    sourceSets {
        main {
            kotlin.srcDirs(
                "../src/main/kotlin",
                "build/generated/ksp/main/kotlin"
            )
        }
    }
}