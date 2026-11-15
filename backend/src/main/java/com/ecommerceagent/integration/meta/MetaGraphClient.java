package com.ecommerceagent.integration.meta;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Graph API calls of the Meta adapter (ADR-0017): account, permissions, ads, one ad, and the pause. Requests go
 * only to the configured base URL; the token is sent in the Authorization header; responses are size-limited.
 */
public class MetaGraphClient {

    static final String AD_FIELDS = "id,name,status,effective_status,adset_id,campaign_id,creative{id}";
    static final int PAGE_SIZE = 100;
    static final int MAX_ADS = 5_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<Integer> RATE_LIMIT_CODES = Set.of(4, 17, 32, 613, 80004);

    private final URI base;
    private final HttpClient http;
    private final Duration timeout;
    private final int maxResponseBytes;

    public MetaGraphClient(URI graphBaseUrl, String apiVersion, Duration connectTimeout, Duration timeout,
            int maxResponseBytes) {
        String root = graphBaseUrl.toString().replaceAll("/+$", "");
        this.base = URI.create(root + "/" + apiVersion + "/");
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    public URI base() {
        return base;
    }

    public MetaAccount account(String token, String accountId) {
        JsonNode body = get(token, base.resolve("act_" + accountId + "?fields=" + encode("id,name,account_status,currency")));
        return new MetaAccount(body.path("id").asString(""), body.path("name").asString(""),
                body.path("account_status").asInt(0), body.path("currency").asString(null));
    }

    /** Permissions the token holds with status {@code granted}. */
    public Set<String> grantedPermissions(String token) {
        JsonNode body = get(token, base.resolve("me/permissions"));
        Set<String> granted = new HashSet<>();
        for (JsonNode item : body.path("data")) {
            if ("granted".equals(item.path("status").asString(""))) {
                granted.add(item.path("permission").asString(""));
            }
        }
        return granted;
    }

    /** All ads of the account, following paging links on the same host and path only. */
    public List<MetaAd> ads(String token, String accountId) {
        URI first = base.resolve("act_" + accountId + "/ads?fields=" + encode(AD_FIELDS) + "&limit=" + PAGE_SIZE);
        List<MetaAd> ads = new ArrayList<>();
        URI next = first;
        while (next != null) {
            JsonNode body = get(token, next);
            for (JsonNode item : body.path("data")) {
                ads.add(ad(item));
                if (ads.size() > MAX_ADS) {
                    throw new MetaApiException(MetaApiException.Kind.UNEXPECTED_RESPONSE, "Too many ads", null);
                }
            }
            String link = body.path("paging").path("next").asString(null);
            next = link == null ? null : sameEndpoint(first, URI.create(link));
        }
        return ads;
    }

    public MetaAd ad(String token, String adId) {
        return ad(get(token, base.resolve(adId + "?fields=" + encode(AD_FIELDS))));
    }

    /** Sets the ad's own status to PAUSED. A timeout after sending is UNCERTAIN: the caller must read the ad again. */
    public void pause(String token, String adId) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(adId)).timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("status=PAUSED")).build();
        JsonNode body = send(request, true);
        if (!body.path("success").asBoolean(false)) {
            throw new MetaApiException(MetaApiException.Kind.UNEXPECTED_RESPONSE, "Pause not confirmed", null);
        }
    }

    private static MetaAd ad(JsonNode item) {
        return new MetaAd(item.path("id").asString(""), item.path("name").asString(""),
                item.path("status").asString(""), item.path("effective_status").asString(""),
                item.path("adset_id").asString(null), item.path("campaign_id").asString(null),
                item.path("creative").path("id").asString(null));
    }

    private URI sameEndpoint(URI first, URI next) {
        if (!next.getScheme().equals(first.getScheme()) || !next.getHost().equals(first.getHost())
                || next.getPort() != first.getPort() || !next.getPath().endsWith(first.getPath())
                || next.getUserInfo() != null) {
            throw new MetaApiException(MetaApiException.Kind.UNEXPECTED_RESPONSE, "Paging left the endpoint", null);
        }
        // Meta's link may carry the token as a parameter: strip it, the header carries it.
        String query = Optional.ofNullable(next.getRawQuery()).orElse("");
        String cleaned = String.join("&", java.util.Arrays.stream(query.split("&"))
                .filter(part -> !part.startsWith("access_token=")).toList());
        return URI.create(first.getScheme() + "://" + first.getRawAuthority() + next.getRawPath()
                + (cleaned.isEmpty() ? "" : "?" + cleaned));
    }

    private JsonNode get(String token, URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "Bearer " + token).GET().build();
        return send(request, false);
    }

    private JsonNode send(HttpRequest request, boolean write) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new MetaApiException(write ? MetaApiException.Kind.UNCERTAIN : MetaApiException.Kind.UNREACHABLE,
                    "Timeout", null);
        } catch (IOException e) {
            throw new MetaApiException(write ? MetaApiException.Kind.UNCERTAIN : MetaApiException.Kind.UNREACHABLE,
                    e.getClass().getSimpleName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetaApiException(MetaApiException.Kind.UNREACHABLE, "Interrupted", null);
        }
        JsonNode body;
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new MetaApiException(MetaApiException.Kind.UNEXPECTED_RESPONSE, "Response too large", null);
            }
            body = JSON.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            if (e instanceof MetaApiException meta) {
                throw meta;
            }
            throw new MetaApiException(write ? MetaApiException.Kind.UNCERTAIN
                    : MetaApiException.Kind.UNEXPECTED_RESPONSE, "Unreadable response", null);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300 && !body.has("error")) {
            return body;
        }
        JsonNode error = body.path("error");
        int code = error.path("code").asInt(0);
        Duration retryAfter = response.headers().firstValue("Retry-After").map(value -> {
            try {
                return Duration.ofSeconds(Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
        MetaApiException.Kind kind;
        if (status == 429 || RATE_LIMIT_CODES.contains(code)) {
            kind = MetaApiException.Kind.RATE_LIMITED;
        } else if (code == 190 || status == 401) {
            kind = MetaApiException.Kind.AUTH_FAILED;
        } else if (code == 10 || (code >= 200 && code < 300) || status == 403) {
            kind = MetaApiException.Kind.PERMISSION_DENIED;
        } else if (status == 404 || (code == 100 && error.path("error_subcode").asInt(0) == 33)) {
            kind = MetaApiException.Kind.NOT_FOUND;
        } else {
            kind = MetaApiException.Kind.UNEXPECTED_RESPONSE;
        }
        throw new MetaApiException(kind, "Graph API error " + status + "/" + code, retryAfter);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
