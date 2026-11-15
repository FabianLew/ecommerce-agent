package com.ecommerceagent.web;

import static com.ecommerceagent.web.IntegrationApiTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-0027 section 6.1 (E-54.5): requests that start work are limited per user and per store, webhook deliveries
 * per endpoint key, before the handler runs; over the limit the API answers 429 with Retry-After. Safety actions are
 * never limited.
 */
@SpringBootTest(properties = {
        "operator.api-rate-limits.version=api-rate-limits/901",
        "operator.api-rate-limits.work-per-user-per-minute=3",
        "operator.api-rate-limits.work-per-store-per-minute=5",
        "operator.api-rate-limits.webhooks-per-endpoint-key-per-minute=2"})
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ApiRateLimitTest {

    @Autowired
    MockMvc mvc;

    /** Runs the requests inside one minute window; a window that turned over during them is run again. */
    private List<MockHttpServletResponse> inOneWindow(int count, Request request) throws Exception {
        while (true) {
            Instant window = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                responses.add(request.send());
            }
            if (Instant.now().truncatedTo(ChronoUnit.MINUTES).equals(window)) {
                return responses;
            }
        }
    }

    interface Request {
        MockHttpServletResponse send() throws Exception;
    }

    @Test
    void workStartedByOneUserIsLimitedPerMinute() throws Exception {
        String subject = "rate-" + UUID.randomUUID();
        UUID store = UUID.randomUUID();
        List<MockHttpServletResponse> responses = inOneWindow(4, () -> mvc.perform(
                post("/api/v1/stores/{storeId}/integrations/{id}/checks", store, UUID.randomUUID())
                        .with(user(subject))).andReturn().getResponse());

        assertThat(responses.subList(0, 3)).allSatisfy(response -> assertThat(response.getStatus()).isNotEqualTo(429));
        MockHttpServletResponse limited = responses.get(3);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").contains("\"retryable\":true");
        assertThat(Integer.parseInt(limited.getHeader("Retry-After"))).isBetween(1, 60);
    }

    @Test
    void oneStoreIsLimitedWhoeverAsks() throws Exception {
        UUID store = UUID.randomUUID();
        List<MockHttpServletResponse> responses = inOneWindow(6, () -> mvc.perform(
                post("/api/v1/stores/{storeId}/integrations/{id}/sync-jobs", store, UUID.randomUUID())
                        .with(user("rate-" + UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"INITIAL_14_DAYS\"}")).andReturn().getResponse());

        assertThat(responses.subList(0, 5)).allSatisfy(response -> assertThat(response.getStatus()).isNotEqualTo(429));
        assertThat(responses.get(5).getStatus()).isEqualTo(429);
    }

    @Test
    void webhookDeliveriesAreLimitedPerEndpointKey() throws Exception {
        String key = "k" + UUID.randomUUID().toString().replace("-", "");
        List<MockHttpServletResponse> responses = inOneWindow(3, () -> mvc.perform(
                post("/api/v1/webhooks/woocommerce/{endpointKey}", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{}")).andReturn().getResponse());

        assertThat(responses.subList(0, 2)).allSatisfy(response -> assertThat(response.getStatus()).isNotEqualTo(429));
        assertThat(responses.get(2).getStatus()).isEqualTo(429);
    }

    @Test
    void stoppingMutationsIsNeverLimited() throws Exception {
        String subject = "rate-" + UUID.randomUUID();
        UUID store = UUID.randomUUID();
        List<MockHttpServletResponse> responses = inOneWindow(6, () -> mvc.perform(
                post("/api/v1/stores/{storeId}/mutation-stop", store).with(user(subject))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stopped\":true,\"reason\":\"test\"}"))
                .andReturn().getResponse());

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatus()).isNotEqualTo(429));
    }
}
