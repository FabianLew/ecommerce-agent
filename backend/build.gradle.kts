import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Versions are fixed by ADR-0001. Change them only together with that ADR.
plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "com.ecommerceagent"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springModulithVersion = "2.1.2"
// Spring AI is the ModelGateway adapter only; the runtime owns the tool loop (ADR-0002, ADR-0016).
val springAiVersion = "2.0.2"
val temporalVersion = "1.40.0"
// OpenAPI contract for the generated frontend client (B-09, ADR-0006).
val springdocVersion = "3.1.1"
// JSON Schema validation of DecisionProposal v1 (ADR-0012); 3.x is built on Jackson 3.
val jsonSchemaValidatorVersion = "3.0.1"

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("io.temporal:temporal-spring-boot-starter:$temporalVersion")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocVersion")
    implementation("com.networknt:json-schema-validator:$jsonSchemaValidatorVersion")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    // Skill manifests (knowledge/skills/*/manifest.yaml, ADR-0016).
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Contract schemas live in /knowledge and are shared with tests and prompts (ADR-0012).
tasks.processResources {
    from("../knowledge/schemas") {
        include("*.schema.json")
        into("schemas")
    }
    // Approved skill packages and prompts, hashed and pinned by every agent run (ADR-0016).
    from("../knowledge/skills") {
        into("knowledge/skills")
    }
    from("../knowledge/prompts") {
        into("knowledge/prompts")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing"))
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Never let tests pick up a developer's real Anthropic endpoint or key (see CLAUDE.md).
    environment("ANTHROPIC_BASE_URL", "")
    environment("ANTHROPIC_API_KEY", "")
    if (project.hasProperty("updateOpenApi")) {
        systemProperty("updateOpenApi", "true")
    }
    if (project.hasProperty("recordTemporalFixtures")) {
        systemProperty("recordTemporalFixtures", "true")
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
