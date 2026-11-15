package com.ecommerceagent.intelligence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.intelligence.agent.AgentSettings;
import com.ecommerceagent.intelligence.agent.ModelGatewayRouter;
import com.ecommerceagent.intelligence.knowledge.KnowledgeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The quality report against the real model (B-36). Runs only with {@code OPERATOR_QUALITY_LIVE=true}, an
 * {@code ANTHROPIC_API_KEY} and an explicit {@code OPERATOR_ANTHROPIC_BASE_URL} (CLAUDE.md: never an implicit
 * endpoint). It spends money: about one agent run per case with the configured model policy. Agreement is reported in
 * {@code build/reports/quality-live/}, never asserted; the run fails only if the harness itself breaks.
 */
@EnabledIfEnvironmentVariable(named = "OPERATOR_QUALITY_LIVE", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPERATOR_ANTHROPIC_BASE_URL", matches = "https://.+")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LiveQualityReportTest {

    @Autowired
    ModelGatewayRouter gateways;
    @Autowired
    AgentSettings configured;
    @Autowired
    KnowledgeRegistry knowledge;

    @Test
    void reportOnTheConfiguredModelPolicy() {
        // The agent is off by default everywhere; this run switches it on for the harness only.
        AgentSettings settings = new AgentSettings(true, configured.models(), configured.effort(),
                configured.maxOutputTokensPerCall(), configured.priceList());
        Clock clock = Clock.systemUTC();
        QualityHarness harness = new QualityHarness(knowledge, clock);

        QualityHarness.Report report = harness.run(TestScopes.system(UUID.randomUUID(), UUID.randomUUID()),
                QualityHarness.agent("LIVE " + String.join(" > ", settings.models()), gateways, settings, knowledge,
                        clock));

        Path markdown = QualityHarness.write(report, Path.of("build/reports/quality-live"));
        assertThat(markdown).exists();
        assertThat(report.rows()).allSatisfy(row -> assertThat(row.agentStats()).isNotNull());
    }
}
