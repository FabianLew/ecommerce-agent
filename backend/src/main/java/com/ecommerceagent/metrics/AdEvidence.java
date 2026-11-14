package com.ecommerceagent.metrics;

import java.time.Clock;
import java.util.List;

import com.ecommerceagent.commerce.Ad;
import com.ecommerceagent.commerce.AdMapping;
import com.ecommerceagent.commerce.MappedAd;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evidence of an ad mapped to a variant (ADR-0011/0012): the ad's state ({@code AD:{id}}) and its mapping
 * ({@code AD_MAPPING:{id}}, with {@code adId} and {@code variantIds}, which the proposal validator relies on).
 */
@Service
public class AdEvidence {

    private final EvidenceStore store;
    private final Clock clock;

    AdEvidence(EvidenceStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public record MappedAdEvidence(Evidence state, Evidence mapping) {
    }

    @Transactional
    public MappedAdEvidence record(TrustedStoreScope scope, MappedAd mapped) {
        Ad ad = mapped.ad();
        AdMapping mapping = mapped.mapping();
        ObjectNode state = JsonNodeFactory.instance.objectNode();
        state.put("adId", ad.id().toString());
        state.put("platform", ad.platform());
        state.put("externalId", ad.externalId());
        state.put("accountExternalId", ad.accountExternalId());
        state.put("name", ad.name());
        state.put("ownStatus", ad.ownStatus().name());
        state.put("effectiveStatus", ad.effectiveStatus());
        state.put("creativeFingerprint", ad.creativeFingerprint());
        state.put("source", ad.source().name());
        Evidence stateEvidence = store.record(scope, new EvidenceDraft(EvidenceType.AD_STATE,
                EvidenceDraft.adRef(ad.id()), null, state, List.of(), ad.observedAt(), ad.observedAt(),
                clock.instant()));

        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("adId", ad.id().toString());
        content.put("mappingId", mapping.id().toString());
        content.put("mappingVersion", mapping.version());
        content.put("scope", mapping.scope().name());
        content.put("verification", mapping.verification().name());
        content.put("verifiedAt", mapping.verifiedAt() == null ? null : mapping.verifiedAt().toString());
        content.put("verifiedCreativeFingerprint", mapping.verifiedCreativeFingerprint());
        content.put("currentForCreative", mapping.currentFor(ad));
        var variants = content.putArray("variantIds");
        mapping.variantIds().stream().map(Object::toString).sorted().forEach(variants::add);
        var now = clock.instant();
        Evidence mappingEvidence = store.record(scope, new EvidenceDraft(EvidenceType.AD_MAPPING,
                EvidenceDraft.adMappingRef(mapping.id()), null, content, List.of(), now, now, now));
        return new MappedAdEvidence(stateEvidence, mappingEvidence);
    }
}
