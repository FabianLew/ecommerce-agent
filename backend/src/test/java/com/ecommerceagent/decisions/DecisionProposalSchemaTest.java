package com.ecommerceagent.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.ecommerceagent.metrics.CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** B-21 (ADR-0012): the shared v1 schema, its examples and the Java mapping agree. */
class DecisionProposalSchemaTest {

    static final Path EXAMPLES = Path.of("../knowledge/schemas/examples");

    @Test
    void everyExamplePassesTheSchemaAndRoundTripsThroughJava() throws IOException {
        List<Path> examples;
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            examples = files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(examples).hasSizeGreaterThanOrEqualTo(5);
        for (Path example : examples) {
            JsonNode document = CanonicalJson.read(Files.readString(example));
            assertThat(ProposalSchema.validate(document)).as(example.toString()).isEmpty();
            DecisionProposal proposal = ProposalJson.read(document);
            assertThat(CanonicalJson.write(ProposalJson.write(proposal))).as(example.toString())
                    .isEqualTo(CanonicalJson.write(document));
        }
        DecisionProposal pause = ProposalJson.read(example("recommend-pause-ad.json"));
        assertThat(pause.recommendation().action()).isInstanceOf(PauseAd.class);
        assertThat(pause.recommendation().disposition()).isEqualTo(DecisionDisposition.RECOMMEND_ACTION);
    }

    @Test
    void unknownFieldsWrongValuesAndBrokenDispositionInvariantsAreRejected() throws IOException {
        ObjectNode unknownField = example("recommend-pause-ad.json");
        unknownField.withObject("recommendation").put("confidence", 1);
        assertThat(codes(unknownField)).containsExactly(ProposalSchema.SCHEMA_VIOLATION);

        ObjectNode badDisposition = example("no-action.json");
        badDisposition.withObject("recommendation").put("disposition", "ACT");
        assertThat(codes(badDisposition)).isNotEmpty();

        ObjectNode actionWithNoAction = example("no-action.json");
        actionWithNoAction.withObject("recommendation").set("action",
                example("recommend-pause-ad.json").get("recommendation").get("action"));
        assertThat(codes(actionWithNoAction)).isNotEmpty();

        ObjectNode recommendWithoutAction = example("recommend-pause-ad.json");
        recommendWithoutAction.withObject("recommendation").putNull("action");
        assertThat(codes(recommendWithoutAction)).isNotEmpty();

        ObjectNode insufficientWithoutRequirements = example("insufficient-data.json");
        insufficientWithoutRequirements.withObject("recommendation").putArray("missingRequirements");
        assertThat(codes(insufficientWithoutRequirements)).isNotEmpty();

        ObjectNode otherTarget = example("recommend-pause-ad.json");
        otherTarget.withObject("recommendation").withObject("action").put("targetType", "META_CAMPAIGN");
        assertThat(codes(otherTarget)).isNotEmpty();

        ObjectNode budget = example("recommend-pause-ad.json");
        budget.withObject("recommendation").withObject("action").withObject("parameters")
                .put("dailyBudget", 100);
        assertThat(codes(budget)).isNotEmpty();

        ObjectNode legacyEvidence = example("monitor.json");
        legacyEvidence.withObject("recommendation").putArray("evidenceIds").add("11111111-1111-4111-8111-111111111111");
        assertThat(codes(legacyEvidence)).isNotEmpty();

        ObjectNode wrongVersion = example("monitor.json");
        wrongVersion.put("schemaVersion", "1.2");
        assertThat(codes(wrongVersion)).isNotEmpty();
    }

    private static List<String> codes(JsonNode document) {
        return ProposalSchema.validate(document).stream().map(ProposalError::code).distinct().toList();
    }

    private static ObjectNode example(String name) throws IOException {
        return (ObjectNode) CanonicalJson.read(Files.readString(EXAMPLES.resolve(name)));
    }
}
