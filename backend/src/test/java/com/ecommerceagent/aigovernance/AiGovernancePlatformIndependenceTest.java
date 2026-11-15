package com.ecommerceagent.aigovernance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR-0027 section 10, platform independence: AI governance and the governed model path know only the trusted
 * organization and store, the automation type, the provider and the model. They never depend on a commerce or ad
 * integration and never name a platform, so a new integration cannot change how AI spend is governed.
 */
class AiGovernancePlatformIndependenceTest {

    private static final Pattern PLATFORM = Pattern.compile(
            "(?i)\\b(woocommerce|woo|shopify|magento|prestashop|allegro|meta ads|facebook|instagram|google ads"
                    + "|tiktok|graph api)\\b");

    static JavaClasses application;

    @BeforeAll
    static void importApplication() {
        application = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ecommerceagent");
    }

    @Test
    void governanceDependsOnlyOnIdentity() {
        classes().that().resideInAPackage("com.ecommerceagent.aigovernance..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("com.ecommerceagent.aigovernance..",
                        "com.ecommerceagent.identity..", "java..", "javax..", "jakarta..", "org.springframework..",
                        "org.slf4j..", "io.micrometer..", "tools.jackson..", "com.fasterxml.jackson..")
                .because("AI governance must not know any commerce or ad platform (ADR-0027 section 10)")
                .check(application);
    }

    @Test
    void theGovernedModelPathDoesNotDependOnIntegrations() {
        classes().that().resideInAPackage("com.ecommerceagent.intelligence.model..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages("com.ecommerceagent.integration..",
                        "com.ecommerceagent.commerce..", "com.ecommerceagent.automation..",
                        "com.ecommerceagent.marketing..")
                .because("the governed model path is platform independent (ADR-0027 section 10)")
                .check(application);
    }

    @Test
    void governanceSourcesAndMigrationsNameNoPlatform() throws IOException {
        List<Path> files;
        try (Stream<Path> java = Files.walk(Path.of("src/main/java/com/ecommerceagent/aigovernance"));
                Stream<Path> model = Files.list(Path.of("src/main/java/com/ecommerceagent/intelligence/model"));
                Stream<Path> sql = Files.list(Path.of("src/main/resources/db/migration"))) {
            files = Stream.of(java.filter(Files::isRegularFile),
                    model.filter(Files::isRegularFile),
                    sql.filter(path -> path.getFileName().toString().matches("V\\d+__ai_.*\\.sql")))
                    .flatMap(s -> s).toList();
        }
        assertThat(files).hasSizeGreaterThan(10);
        for (Path file : files) {
            assertThat(PLATFORM.matcher(Files.readString(file)).find()).as(file.toString()).isFalse();
        }
    }
}
