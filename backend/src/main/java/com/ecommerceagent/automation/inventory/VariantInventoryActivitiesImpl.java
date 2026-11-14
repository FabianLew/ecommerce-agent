package com.ecommerceagent.automation.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityErrors;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.decisions.InventoryRiskDetector;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.RiskAssessment;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.WooSourceRefresh;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class VariantInventoryActivitiesImpl implements VariantInventoryActivities {

    private final WooSourceRefresh refresh;
    private final InventoryRiskDetector detector;
    private final Clock clock;

    VariantInventoryActivitiesImpl(WooSourceRefresh refresh, InventoryRiskDetector detector, Clock clock) {
        this.refresh = refresh;
        this.detector = detector;
        this.clock = clock;
    }

    @Override
    public String verify(String variantId) {
        return ActivityErrors.guarded(() -> refresh.refreshVariant(ActivityStoreScope.current(),
                UUID.fromString(variantId)));
    }

    @Override
    public RiskCheck assessRisk(String variantId) {
        return check(detector.assess(ActivityStoreScope.current(), UUID.fromString(variantId), null));
    }

    @Override
    public RiskCheck verifyAndAssess(String variantId) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        UUID id = UUID.fromString(variantId);
        Instant readAt = clock.instant();
        String outcome = ActivityErrors.guarded(() -> refresh.refreshVariant(scope, id));
        // A read that recorded the stock, or found it unchanged, confirms it as of the read.
        Instant confirmedAt = confirms(outcome) ? readAt : null;
        return check(detector.assess(scope, id, confirmedAt));
    }

    static boolean confirms(String outcome) {
        return outcome != null && !outcome.isEmpty() && Arrays.stream(outcome.split(","))
                .allMatch(part -> part.equals("RECORDED") || part.equals("DUPLICATE"));
    }

    private RiskCheck check(RiskAssessment assessment) {
        Long next = assessment.nextCheckAt() == null ? null
                : Math.max(1, Duration.between(clock.instant(), assessment.nextCheckAt()).toSeconds());
        Opportunity opportunity = assessment.opportunity();
        return new RiskCheck(assessment.observation().outcome().name(), assessment.observation().reason(),
                opportunity == null ? null : opportunity.id().toString(), assessment.opened(), next);
    }
}
