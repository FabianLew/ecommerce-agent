package com.ecommerceagent.intelligence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.decisions.ProposalJson;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.agent.AgentSettings;
import com.ecommerceagent.intelligence.agent.FakeModelGateway;
import com.ecommerceagent.intelligence.agent.MutableClock;
import com.ecommerceagent.intelligence.knowledge.KnowledgeRegistry;
import com.ecommerceagent.metrics.CanonicalJson;
import com.ecommerceagent.intelligence.model.provider.ProviderGateways;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * The quality harness itself (B-36). Without a model the report covers the baseline only; the baseline is the
 * deterministic reference, so it must meet every expert case. The agent column is checked on a scripted model.
 */
class QualityReportTest {

    final KnowledgeRegistry knowledge = new KnowledgeRegistry();
    final MutableClock clock = new MutableClock(InventoryContextFixture.AS_OF);
    final QualityHarness harness = new QualityHarness(knowledge, clock);
    final TrustedStoreScope scope = TestScopes.system(UUID.randomUUID(), UUID.randomUUID());

    @Test
    void baselineMeetsEveryExpertCaseAndTheReportIsWritten(@TempDir Path directory) throws Exception {
        QualityHarness.Report report = harness.run(scope, null);

        assertThat(report.rows()).hasSize(8);
        assertThat(report.rows()).allSatisfy(row -> assertThat(row.baselineMismatches())
                .as(row.expected().id()).isEmpty());
        assertThat(report.agentMode()).isEqualTo("SKIPPED_NO_MODEL");
        Path markdown = QualityHarness.write(report, directory);
        assertThat(Files.readString(markdown)).contains("Baseline: 8 / 8").contains("| IG-Q-08");
        assertThat(CanonicalJson.read(Files.readString(directory.resolve("inventory-guardian.json")))
                .get("baselineAgreements").asInt()).isEqualTo(8);
        // The report of the default test run lands in build/reports/quality for CI artifacts.
        QualityHarness.write(report, Path.of("build/reports/quality"));
    }

    @Test
    void agentDisagreementIsReportedNotFailed() {
        FakeModelGateway model = new FakeModelGateway();
        BaselineProposer baseline = new BaselineProposer(QualityHarness.RISK);
        for (QualityHarness.Case expected : harness.cases()) {
            var context = InventoryContext.of(QualityFixtures.snapshot(expected.fixture()));
            ObjectNode answer = ProposalJson.write(baseline.propose(context));
            if (expected.id().equals("IG-Q-02")) {
                // The scripted agent pauses the catalog ad: the report must show it, not hide it.
                answer = ProposalJson.write(baseline.propose(InventoryContext.of(
                        QualityFixtures.snapshot("serum-dedicated-and-catalog"))));
                ((ObjectNode) answer.get("action")).put("targetId", InventoryContextFixture.AD_9002.toString());
            }
            model.thenText(CanonicalJson.write(answer));
        }
        AgentSettings settings = new AgentSettings(true, List.of("fake/primary"), "low");

        QualityHarness.Report report = harness.run(scope, QualityHarness.agent("SCRIPTED",
                ProviderGateways.of(model, "fake"), settings, knowledge, clock));

        assertThat(report.agentAgreements()).isEqualTo(7);
        QualityHarness.Row wrong = report.rows().stream().filter(row -> row.expected().id().equals("IG-Q-02"))
                .findFirst().orElseThrow();
        assertThat(wrong.agent().pausesExternalId()).isEqualTo("ad-9002");
        assertThat(wrong.agentMismatches()).contains("disposition RECOMMEND_ACTION instead of ESCALATE",
                "pauses ad-9002 although no action is expected");
        assertThat(wrong.agentStats().status()).isEqualTo("SUCCEEDED");
        assertThat(wrong.agentStats().modelCalls()).isEqualTo(1);
        // 500 × 4 / 1M + 100 × 20 / 1M per case
        assertThat(report.agentCostUsd()).isEqualByComparingTo("0.032");
    }
}
