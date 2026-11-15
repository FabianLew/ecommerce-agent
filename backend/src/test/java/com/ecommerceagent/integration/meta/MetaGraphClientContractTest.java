package com.ecommerceagent.integration.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import com.ecommerceagent.integration.meta.GraphApiStub.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contract tests of the Graph API calls against a local stub (ADR-0017 verification). */
class MetaGraphClientContractTest {

    static final String TOKEN = GraphApiStub.TOKEN;

    GraphApiStub stub;
    MetaGraphClient client;

    @BeforeEach
    void setUp() throws Exception {
        stub = new GraphApiStub();
        client = new MetaGraphClient(stub.baseUrl(), "v23.0", Duration.ofSeconds(2), Duration.ofMillis(800), 20_000);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    static String ad(String id, String status, String effective, String creative) {
        return "{\"id\":\"" + id + "\",\"name\":\"Ad " + id + "\",\"status\":\"" + status + "\",\"effective_status\":\""
                + effective + "\",\"adset_id\":\"55\",\"campaign_id\":\"66\",\"creative\":{\"id\":\"" + creative + "\"}}";
    }

    @Test
    void readsTheAccountAndGrantedPermissionsWithTheTokenInTheHeaderOnly() {
        stub.on("GET act_123", Reply.ok("{\"id\":\"act_123\",\"name\":\"Shop\",\"account_status\":1,\"currency\":\"PLN\"}"));
        stub.on("GET me/permissions", Reply.ok("{\"data\":[{\"permission\":\"ads_read\",\"status\":\"granted\"},"
                + "{\"permission\":\"ads_management\",\"status\":\"declined\"}]}"));

        MetaAccount account = client.account(TOKEN, "123");
        assertThat(account).isEqualTo(new MetaAccount("act_123", "Shop", 1, "PLN"));
        assertThat(client.grantedPermissions(TOKEN)).containsExactly("ads_read");

        assertThat(stub.requests).allSatisfy(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.uri().toString()).doesNotContain(TOKEN).doesNotContain("access_token");
            assertThat(request.uri().getPath()).startsWith("/v23.0/");
        });
        assertThat(stub.requests.getFirst().uri().getRawQuery()).isEqualTo("fields=id%2Cname%2Caccount_status%2Ccurrency");
    }

    @Test
    void followsPagingOnTheSameEndpointAndStripsATokenFromTheLink() {
        String second = stub.baseUrl() + "/v23.0/act_123/ads?fields=x&limit=100&after=CUR2&access_token=" + TOKEN;
        stub.on("GET act_123/ads", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (query.contains("after=CUR2")) {
                return Reply.ok("{\"data\":[" + ad("3", "PAUSED", "PAUSED", "c3") + "]}");
            }
            return Reply.ok("{\"data\":[" + ad("1", "ACTIVE", "ACTIVE", "c1") + "," + ad("2", "ACTIVE",
                    "CAMPAIGN_PAUSED", "c2") + "],\"paging\":{\"cursors\":{\"after\":\"CUR2\"},\"next\":\"" + second
                    + "\"}}");
        });

        List<MetaAd> ads = client.ads(TOKEN, "123");

        assertThat(ads).extracting(MetaAd::id).containsExactly("1", "2", "3");
        assertThat(ads.get(1)).isEqualTo(new MetaAd("2", "Ad 2", "ACTIVE", "CAMPAIGN_PAUSED", "55", "66", "c2"));
        assertThat(stub.requests).hasSize(2);
        assertThat(stub.requests.getFirst().uri().getRawQuery())
                .isEqualTo("fields=id%2Cname%2Cstatus%2Ceffective_status%2Cadset_id%2Ccampaign_id%2Ccreative%7Bid%7D"
                        + "&limit=100");
        assertThat(stub.requests.get(1).uri().getRawQuery()).doesNotContain("access_token").contains("after=CUR2");
    }

    @Test
    void refusesAPagingLinkToAnotherHostOrPath() {
        stub.on("GET act_123/ads", Reply.ok("{\"data\":[],\"paging\":{\"next\":\"https://evil.example/v23.0/act_123/ads?after=X\"}}"));
        assertThatThrownBy(() -> client.ads(TOKEN, "123")).isInstanceOf(MetaApiException.class)
                .extracting(e -> ((MetaApiException) e).kind()).isEqualTo(MetaApiException.Kind.UNEXPECTED_RESPONSE);

        stub.reset();
        stub.on("GET act_123/ads", Reply.ok("{\"data\":[],\"paging\":{\"next\":\"" + stub.baseUrl()
                + "/v23.0/act_999/ads?after=X\"}}"));
        assertThatThrownBy(() -> client.ads(TOKEN, "123")).isInstanceOf(MetaApiException.class);
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void mapsErrorsToTypedKindsWithoutTheResponseBody() {
        stub.on("GET act_1", Reply.error(400, 190, 463));
        stub.on("GET act_2", Reply.error(400, 17, 2446079).withHeader("Retry-After", "120"));
        stub.on("GET act_3", Reply.error(429, 0, 0));
        stub.on("GET act_4", Reply.error(403, 10, 0));
        stub.on("GET act_5", Reply.error(400, 80004, 2446079));
        stub.on("GET act_6", Reply.error(500, 2, 0));

        assertKind("1", MetaApiException.Kind.AUTH_FAILED);
        MetaApiException limited = assertKind("2", MetaApiException.Kind.RATE_LIMITED);
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(120));
        assertKind("3", MetaApiException.Kind.RATE_LIMITED);
        assertKind("4", MetaApiException.Kind.PERMISSION_DENIED);
        assertKind("5", MetaApiException.Kind.RATE_LIMITED);
        assertKind("6", MetaApiException.Kind.UNEXPECTED_RESPONSE);
        // Unknown object (code 100, subcode 33).
        assertKind("7", MetaApiException.Kind.NOT_FOUND);
    }

    @Test
    void aWrongTokenIsAnAuthFailure() {
        stub.on("GET act_123", Reply.ok("{}"));
        assertThatThrownBy(() -> client.account("EAAwrongtoken0000000000000000", "123"))
                .isInstanceOf(MetaApiException.class)
                .satisfies(e -> {
                    assertThat(((MetaApiException) e).kind()).isEqualTo(MetaApiException.Kind.AUTH_FAILED);
                    assertThat(e.getMessage()).doesNotContain("EAAwrong");
                });
    }

    @Test
    void pausesWithAFormBodyAndTreatsATimeoutAfterSendingAsUncertain() {
        stub.on("POST 9001", Reply.ok("{\"success\":true}"));
        client.pause(TOKEN, "9001");
        GraphApiStub.Recorded write = stub.requests.getLast();
        assertThat(write.method()).isEqualTo("POST");
        assertThat(write.body()).isEqualTo("status=PAUSED");
        assertThat(write.uri().getRawQuery()).isNull();

        stub.on("POST 9002", Reply.ok("{\"success\":true}").delayed(2_000));
        assertThatThrownBy(() -> client.pause(TOKEN, "9002")).isInstanceOf(MetaApiException.class)
                .extracting(e -> ((MetaApiException) e).kind()).isEqualTo(MetaApiException.Kind.UNCERTAIN);

        stub.on("POST 9003", Reply.ok("{\"success\":false}"));
        assertThatThrownBy(() -> client.pause(TOKEN, "9003")).isInstanceOf(MetaApiException.class)
                .extracting(e -> ((MetaApiException) e).kind()).isEqualTo(MetaApiException.Kind.UNEXPECTED_RESPONSE);
    }

    @Test
    void aReadTimeoutIsUnreachableAndAnOversizedResponseIsRejected() {
        stub.on("GET 1", Reply.ok(ad("1", "ACTIVE", "ACTIVE", "c")).delayed(2_000));
        assertKindOf(() -> client.ad(TOKEN, "1"), MetaApiException.Kind.UNREACHABLE);

        stub.on("GET 2", Reply.ok("{\"name\":\"" + "x".repeat(30_000) + "\"}"));
        assertKindOf(() -> client.ad(TOKEN, "2"), MetaApiException.Kind.UNEXPECTED_RESPONSE);
    }

    private MetaApiException assertKind(String account, MetaApiException.Kind kind) {
        return assertKindOf(() -> client.account(TOKEN, account), kind);
    }

    private static MetaApiException assertKindOf(Runnable call, MetaApiException.Kind kind) {
        try {
            call.run();
        } catch (MetaApiException e) {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.getMessage()).doesNotContain("stub");
            return e;
        }
        throw new AssertionError("expected " + kind);
    }
}
