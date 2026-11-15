package com.ecommerceagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundaries from the architecture plan (B2) are enforced on every build. */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(OperatorApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    @Test
    void allPlannedModulesAreDetected() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder(
                        "identity", "integration", "commerce", "businessprofile", "metrics",
                        "decisions", "support", "reporting", "intelligence", "automation", "web", "aigovernance");
    }
}
