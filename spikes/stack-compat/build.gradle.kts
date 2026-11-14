import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Spike B-02/B-03: verifies that the chosen stack versions work together and that
// Spring AI returns tool calls to our runtime instead of executing them. Not production code.

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "com.ecommerceagent"
version = "0.0.1-SPIKE"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springAiVersion = "2.0.2"
val springModulithVersion = "2.1.2"
val temporalVersion = "1.40.0"

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("io.temporal:temporal-spring-boot-starter:$temporalVersion")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Never let tests pick up a developer's real Anthropic endpoint or key.
    environment("ANTHROPIC_BASE_URL", "")
    environment("ANTHROPIC_API_KEY", "")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
