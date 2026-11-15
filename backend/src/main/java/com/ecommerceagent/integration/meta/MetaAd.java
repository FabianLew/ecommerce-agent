package com.ecommerceagent.integration.meta;

/** One ad as the Graph API returns it, reduced to the fields the operator uses. */
public record MetaAd(String id, String name, String status, String effectiveStatus, String adsetId,
        String campaignId, String creativeId) {
}
