package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed contract {@code contracts/openapi/operator-api.json} must match the running API. The
 * frontend client is generated from that file (ADR-0006). After an intentional API change run
 * {@code ./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi} and regenerate the client.
 */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

    static final Path CONTRACT = Path.of("..", "contracts", "openapi", "operator-api.json");

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Autowired
    MockMvc mvc;

    @Test
    void committedContractMatchesTheApi() throws Exception {
        String body = mvc.perform(get("/v3/api-docs").with(jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String actual = canonical(JSON.readTree(body));

        if (Boolean.getBoolean("updateOpenApi")) {
            Files.writeString(CONTRACT, actual, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(CONTRACT)).as("missing %s, run with -PupdateOpenApi", CONTRACT).isTrue();
        assertThat(actual)
                .as("API changed: run ./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi, then "
                        + "npm run generate:api in frontend/")
                .isEqualTo(Files.readString(CONTRACT, StandardCharsets.UTF_8));
    }

    @Test
    void contractHidesServerResolvedParametersAndDocumentsStoreErrors() throws Exception {
        JsonNode contract = JSON.readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));
        JsonNode store = contract.at("/paths/~1api~1v1~1stores~1{storeId}/get");

        assertThat(store.at("/parameters/0/name").asString()).isEqualTo("storeId");
        assertThat(store.at("/parameters").size()).isEqualTo(1);
        assertThat(store.at("/responses/404/content/application~1json/schema/$ref").asString())
                .isEqualTo("#/components/schemas/ApiError");
        assertThat(contract.at("/paths/~1api~1v1~1me/get/parameters").isMissingNode()).isTrue();
        assertThat(contract.toString()).doesNotContain("TrustedStoreScope").doesNotContain("RegisteredUser");
    }

    private static String canonical(JsonNode node) {
        // Re-serialize with sorted keys so the committed file is stable across runs.
        Object tree = JSON.convertValue(node, Object.class);
        return JSON.writeValueAsString(tree) + "\n";
    }
}
