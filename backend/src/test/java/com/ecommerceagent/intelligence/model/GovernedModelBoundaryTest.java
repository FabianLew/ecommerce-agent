package com.ecommerceagent.intelligence.model;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR-0027 section 10: {@link GovernedModelGateway} is the only production path to a paid model. Proven on the
 * compiled application classes, not by convention: nothing but the governed gateway reaches a provider adapter, calls
 * a model step, or touches a provider SDK.
 */
class GovernedModelBoundaryTest {

    private static final String PROVIDER = "com.ecommerceagent.intelligence.model.provider..";

    static JavaClasses application;

    @BeforeAll
    static void importApplication() {
        application = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ecommerceagent");
    }

    @Test
    void onlyTheGovernedGatewayReachesTheProviderAdapters() {
        noClasses().that().resideOutsideOfPackage(PROVIDER)
                .and().doNotHaveFullyQualifiedName(GovernedModelGateway.class.getName())
                .should().dependOnClassesThat().resideInAPackage(PROVIDER)
                .because("provider adapters stay behind governance (ADR-0027 section 10)")
                .check(application);
    }

    @Test
    void onlyTheGovernedGatewayAsksAProviderForAModelStep() {
        noClasses().that().resideOutsideOfPackage(PROVIDER)
                .and().doNotHaveFullyQualifiedName(GovernedModelGateway.class.getName())
                .should().callMethod(ModelGateway.class, "complete", ModelRequest.class)
                .because("every model step is reserved, marked sent and settled by the governed gateway")
                .check(application);
    }

    @Test
    void onlyProviderAdaptersTouchAModelSdk() {
        noClasses().that().resideOutsideOfPackage(PROVIDER)
                .should().dependOnClassesThat(resideInAPackage("org.springframework.ai.chat..")
                        .or(resideInAPackage("org.springframework.ai.anthropic.."))
                        .or(resideInAPackage("com.anthropic..")))
                .because("a model SDK used elsewhere would be a path to a paid model around governance")
                .check(application);
    }

    @Test
    void theRulesSeeTheClassesTheyGuard() {
        // Guards against a rule that passes because the import found nothing.
        org.assertj.core.api.Assertions.assertThat(application.contain(GovernedModelGateway.class)).isTrue();
        org.assertj.core.api.Assertions.assertThat(application.stream()
                .anyMatch(type -> type.getPackageName().startsWith("com.ecommerceagent.intelligence.model.provider")
                        && type.getSimpleName().equals("SpringAiAnthropicGateway"))).isTrue();
        org.assertj.core.api.Assertions.assertThat(application.contain(
                com.ecommerceagent.intelligence.agent.AgentRuntime.class)).isTrue();
    }
}
