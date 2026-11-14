package com.ecommerceagent.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.ecommerceagent.integration.WebhookReceipt;
import com.ecommerceagent.integration.WebhookRejectedException;
import com.ecommerceagent.integration.WooWebhooks;
import com.ecommerceagent.integration.WooWebhooks.WebhookHeaders;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public WooCommerce webhook endpoint (ADR-0009). No user token: the endpoint key selects the connection and
 * the HMAC signature over the raw body authenticates the shop. The body is never logged.
 */
@RestController
class WooWebhookController {

    private final WooWebhooks webhooks;

    WooWebhookController(WooWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/v1/webhooks/woocommerce/{endpointKey}")
    WebhookReceiptResponse receive(@PathVariable String endpointKey, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > WooWebhooks.MAX_BODY_BYTES) {
            throw new WebhookRejectedException(WebhookRejectedException.Reason.PAYLOAD_TOO_LARGE, "Body too large");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            // Read at most one byte over the limit, so an oversized chunked body is rejected without buffering it.
            body = in.readNBytes(WooWebhooks.MAX_BODY_BYTES + 1);
        }
        String pingId = request.getContentType() != null && body.length == 0
                && request.getContentType().startsWith("application/x-www-form-urlencoded")
                ? request.getParameter("webhook_id") : null;
        if (pingId != null) {
            // A filter already parsed the form body of Woo's ping into parameters.
            body = ("webhook_id=" + pingId).getBytes(StandardCharsets.US_ASCII);
        }
        WebhookHeaders headers = new WebhookHeaders(request.getContentType(),
                request.getHeader("X-WC-Webhook-Topic"), request.getHeader("X-WC-Webhook-Signature"),
                request.getHeader("X-WC-Webhook-ID"), request.getHeader("X-WC-Webhook-Delivery-ID"));
        return new WebhookReceiptResponse(webhooks.receive(endpointKey, headers, body));
    }

    record WebhookReceiptResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) WebhookReceipt receipt) {
    }
}
