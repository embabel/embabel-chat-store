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
    kotlin("jvm") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.10"
}

group = "com.embabel.chat.store"
version = "0.5.0-SNAPSHOT"

// Versions come from the Maven build via -P properties (see the exec-maven-plugin in the pom), so the
// pom is the single source of truth and the KSP DSL is generated against the *same* versions it is
// later compiled against. Previously drivineVersion was hardcoded here, so bumping the pom silently
// generated the DSL against a different Drivine than the one it compiled against. The fallbacks keep a
// standalone `./gradlew kspKotlin` working.
val drivineVersion = (findProperty("drivineVersion") as String?) ?: "0.0.74"
val embabelAgentVersion = (findProperty("embabelAgentVersion") as String?) ?: "1.5.0-SNAPSHOT"
val embabelCommonVersion = (findProperty("embabelCommonVersion") as String?) ?: "2.0.0-SNAPSHOT"

repositories {
    // mavenLocal first is deliberate — it mirrors Maven's local-m2-first resolution, so this codegen
    // and the main Maven compile resolve the *same* drivine / embabel artifacts.
    mavenLocal()
    mavenCentral()
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
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("com.embabel.agent:embabel-agent-api:$embabelAgentVersion")
    implementation("com.embabel.common:embabel-common-core:$embabelCommonVersion")
    implementation("com.fasterxml.uuid:java-uuid-generator:5.2.0")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
}

kotlin {
    compilerOptions {
        // -Xcontext-parameters: required for the Drivine DSL, which uses context parameters.
        // -Xskip-metadata-version-check: consume embabel libs built with an older Kotlin metadata.
        freeCompilerArgs.addAll("-Xcontext-parameters", "-Xskip-metadata-version-check")
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
