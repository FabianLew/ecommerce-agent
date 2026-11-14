package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** B-07: every request has a requestId in the log and in the response; errors follow 04 §3. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, RequestCorrelationTest.FailingEndpoint.class})
@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void everyResponseCarriesGeneratedRequestId() throws Exception {
        MvcResult ok = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();
        MvcResult unauthenticated = mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andReturn();

        String first = ok.getResponse().getHeader("X-Request-Id");
        String second = unauthenticated.getResponse().getHeader("X-Request-Id");
        assertThat(UUID.fromString(first)).isNotNull();
        assertThat(UUID.fromString(second)).isNotNull();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void safeIncomingIdsAreKeptAndUnsafeOnesReplaced() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "edge-req-12345")
                        .header("X-Correlation-Id", "flow-abc-123"))
                .andExpect(header().string("X-Request-Id", "edge-req-12345"))
                .andExpect(header().string("X-Correlation-Id", "flow-abc-123"));

        MvcResult injected = mvc.perform(get("/actuator/health")
                        .header("X-Request-Id", "bad\nid\"}{json")
                        .header("X-Correlation-Id", "x".repeat(200)))
                .andReturn();
        String requestId = injected.getResponse().getHeader("X-Request-Id");
        assertThat(UUID.fromString(requestId)).isNotNull();
        // Without a usable correlation ID the request starts a new flow.
        assertThat(injected.getResponse().getHeader("X-Correlation-Id")).isEqualTo(requestId);
    }

    @Test
    void errorBodiesFollowContractAndQuoteRequestId() throws Exception {
        expectError(get("/api/v1/me"), 401, "UNAUTHENTICATED", false);
        expectError(get("/api/v1/stores/{id}", UUID.randomUUID()).with(authenticated()), 404, "NOT_FOUND", false);
        expectError(get("/api/v1/no-such-endpoint").with(authenticated()), 404, "NOT_FOUND", false);
        expectError(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                .with(authenticated()), 405, "METHOD_NOT_ALLOWED", false);
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails(CapturedOutput output) throws Exception {
        MvcResult result = expectError(get("/api/v1/test-failure").with(authenticated()), 500, "INTERNAL_ERROR", true);

        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-internal-detail");
        String requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(output.getOut()).contains("secret-internal-detail").contains(requestId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void accessLogIsStructuredJsonWithCorrelationFields(CapturedOutput output) throws Exception {
        String requestId = mvc.perform(get("/actuator/health").header("X-Correlation-Id", "flow-log-test-1"))
                .andReturn().getResponse().getHeader("X-Request-Id");

        Map<String, Object> line = output.getOut().lines()
                .filter(l -> l.contains(requestId) && l.contains("GET /actuator/health"))
                .map(l -> JsonMapper.builder().build().readValue(l, Map.class))
                .map(m -> (Map<String, Object>) m)
                .findFirst().orElseThrow();

        assertThat(line).containsEntry("requestId", requestId)
                .containsEntry("correlationId", "flow-log-test-1")
                .containsKeys("traceId", "spanId", "@timestamp");
        assertThat(line.get("message").toString()).startsWith("GET /actuator/health -> 200");
        // No query strings in access logs (they may carry customer data).
        List<String> withQuery = output.getOut().lines().filter(l -> l.contains("email=")).toList();
        assertThat(withQuery).isEmpty();
    }

    /** Real tokens always carry an issuer (validated by the resource server). */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticated() {
        return jwt().jwt(token -> token.issuer("https://issuer.test/realms/operator").subject("correlation-test"));
    }

    private MvcResult expectError(org.springframework.test.web.servlet.RequestBuilder request, int status, String code,
            boolean retryable) throws Exception {
        MvcResult result = mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(retryable))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("\"requestId\":\"" + result.getResponse().getHeader("X-Request-Id") + "\"");
        return result;
    }

    @TestConfiguration
    @RestController
    static class FailingEndpoint {

        @GetMapping("/api/v1/test-failure")
        String fail() {
            throw new IllegalStateException("secret-internal-detail");
        }
    }
}
