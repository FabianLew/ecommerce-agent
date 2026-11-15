package com.ecommerceagent.automation.guardian;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.ecommerceagent.automation.execution.ActionExecution;
import com.ecommerceagent.automation.execution.AuthorizedAction;
import com.ecommerceagent.automation.execution.ExecutionAuthority;
import com.ecommerceagent.automation.execution.ExecutionLedger;
import com.ecommerceagent.automation.notifications.NotificationEvent;
import com.ecommerceagent.automation.notifications.NotificationKind;
import com.ecommerceagent.automation.notifications.OperatorNotifier;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunitySubject;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.stereotype.Component;

/** Builds the guardian's notifications from stored ids only (ADR-0021); the parameters never come from a model. */
@Component
class GuardianNotifications {

    private final OperatorNotifier notifier;
    private final ExecutionLedger ledger;
    private final ExecutionAuthority authority;
    private final DecisionProposals proposals;
    private final Opportunities opportunities;
    private final AdRepository ads;
    private final CatalogRepository catalog;

    GuardianNotifications(OperatorNotifier notifier, ExecutionLedger ledger, ExecutionAuthority authority,
            DecisionProposals proposals, Opportunities opportunities, AdRepository ads, CatalogRepository catalog) {
        this.notifier = notifier;
        this.ledger = ledger;
        this.authority = authority;
        this.proposals = proposals;
        this.opportunities = opportunities;
        this.ads = ads;
        this.catalog = catalog;
    }

    void decisionNeeded(TrustedStoreScope scope, UUID proposalId) {
        StoredProposal stored = proposals.find(scope, proposalId).orElseThrow();
        UUID opportunityId = ((OpportunitySubject) stored.proposal().subject()).opportunityId();
        Map<String, String> parameters = new TreeMap<>(subject(scope, opportunityId));
        if (stored.proposal().recommendation().action() instanceof PauseAd pause) {
            ads.ad(scope, pause.adId()).ifPresent(ad -> {
                parameters.put("adExternalId", ad.externalId());
                parameters.put("adName", ad.name());
            });
        }
        notifier.notify(scope, new NotificationEvent(proposalId + "/" + stored.proposal().revision() + "/DECISION",
                NotificationKind.DECISION_NEEDED, null, parameters, opportunityId, proposalId, null));
    }

    void outcome(TrustedStoreScope scope, UUID executionId) {
        ActionExecution execution = ledger.find(scope, executionId).orElseThrow();
        AuthorizedAction action = authority.find(scope, execution.authorizedActionId()).orElseThrow();
        NotificationKind kind = switch (execution.status()) {
            case VERIFIED -> NotificationKind.ACTION_VERIFIED;
            case NOOP -> NotificationKind.ACTION_NOOP;
            case FAILED -> NotificationKind.ACTION_FAILED;
            case CONFLICT -> NotificationKind.ACTION_CONFLICT;
            default -> NotificationKind.ACTION_UNKNOWN;
        };
        StoredProposal stored = proposals.find(scope, action.proposalId()).orElseThrow();
        UUID opportunityId = ((OpportunitySubject) stored.proposal().subject()).opportunityId();
        Map<String, String> parameters = new TreeMap<>(subject(scope, opportunityId));
        parameters.put("adExternalId", action.targetExternalId());
        ads.ad(scope, action.targetId()).ifPresent(ad -> parameters.put("adName", ad.name()));
        notifier.notify(scope, new NotificationEvent(executionId + "/" + execution.status().name(), kind,
                execution.resultCode(), parameters, opportunityId, action.proposalId(), executionId));
    }

    private Map<String, String> subject(TrustedStoreScope scope, UUID opportunityId) {
        Map<String, String> parameters = new TreeMap<>();
        opportunities.find(scope, opportunityId).filter(o -> Opportunity.PRODUCT_VARIANT.equals(o.subjectType()))
                .flatMap(o -> catalog.variant(scope, o.subjectId()))
                .ifPresent(variant -> {
                    if (variant.sku() != null) {
                        parameters.put("sku", variant.sku());
                    }
                    parameters.put("variantName", variant.name());
                });
        return parameters;
    }
}
