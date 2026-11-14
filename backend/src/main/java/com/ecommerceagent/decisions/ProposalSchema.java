package com.ecommerceagent.decisions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

/** The JSON Schema of DecisionProposal v1, loaded once from the classpath (ADR-0012). */
public final class ProposalSchema {

    public static final String RESOURCE = "/schemas/decision-proposal-v1.schema.json";
    public static final String SCHEMA_VIOLATION = "SCHEMA_VIOLATION";

    private static final Schema SCHEMA = load();

    private ProposalSchema() {
    }

    /** Structural errors of the document; empty when it matches the schema. */
    public static List<ProposalError> validate(JsonNode document) {
        List<Error> errors = SCHEMA.validate(document);
        return errors.stream()
                .map(error -> new ProposalError(SCHEMA_VIOLATION, error.getInstanceLocation().toString(),
                        error.getKeyword() + ": " + error.getMessage()))
                .toList();
    }

    private static Schema load() {
        try (InputStream in = ProposalSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(in, InputFormat.JSON);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
