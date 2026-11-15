package com.ecommerceagent.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.automation.Onboarding;
import com.ecommerceagent.automation.Onboarding.Acknowledgement;
import com.ecommerceagent.automation.Onboarding.DomainState;
import com.ecommerceagent.automation.Onboarding.OnboardingState;
import com.ecommerceagent.automation.Onboarding.Step;
import com.ecommerceagent.automation.Onboarding.StepState;
import com.ecommerceagent.businessprofile.BusinessProfile;
import com.ecommerceagent.businessprofile.BusinessProfiles;
import com.ecommerceagent.businessprofile.BusinessProfiles.ProfileState;
import com.ecommerceagent.businessprofile.ProfileDraftInput;
import com.ecommerceagent.businessprofile.ProfileEnums.Goal;
import com.ecommerceagent.businessprofile.ProfileEnums.MissingField;
import com.ecommerceagent.businessprofile.ProfileEnums.Priority;
import com.ecommerceagent.businessprofile.ProfileEnums.RiskPreference;
import com.ecommerceagent.businessprofile.ProfileEnums.Status;
import com.ecommerceagent.businessprofile.ProfileEnums.TaxBasis;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Store Business Profile and onboarding (B-33, ADR-0018). */
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
class BusinessProfileController {

    private final BusinessProfiles profiles;
    private final Onboarding onboarding;

    BusinessProfileController(BusinessProfiles profiles, Onboarding onboarding) {
        this.profiles = profiles;
        this.onboarding = onboarding;
    }

    @GetMapping("/business-profile")
    ProfileStateResponse profile(TrustedStoreScope scope) {
        ProfileState state = profiles.state(scope);
        return new ProfileStateResponse(ProfileResponse.from(state.approved()), ProfileResponse.from(state.draft()),
                scope.has(StorePermission.STORE_MANAGE));
    }

    @PutMapping("/business-profile/draft")
    ProfileResponse saveDraft(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody DraftRequest request) {
        return ProfileResponse.from(profiles.saveDraft(scope, new ProfileDraftInput(request.goal(),
                request.planningHorizonDays(), request.riskPreference(), request.taxBasis(), request.priorities(),
                request.plannedAdSpendMinor(), request.plannedAdSpendPeriod(), request.note()),
                request.expectedRevision()));
    }

    @PostMapping("/business-profile/draft/approval")
    ProfileResponse approve(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody ApproveRequest request) {
        return ProfileResponse.from(profiles.approve(scope, request.expectedRevision()));
    }

    @GetMapping("/business-profile/versions")
    ListResponse<ProfileResponse> versions(TrustedStoreScope scope) {
        return ListResponse.complete(profiles.history(scope).stream().map(ProfileResponse::from).toList());
    }

    @GetMapping("/onboarding")
    OnboardingResponse onboarding(TrustedStoreScope scope) {
        return OnboardingResponse.from(onboarding.state(scope), scope);
    }

    @PatchMapping("/onboarding")
    OnboardingResponse updateOnboarding(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody OnboardingRequest request) {
        return OnboardingResponse.from(onboarding.update(scope, request.currentStep(), request.acknowledgements(),
                request.expectedVersion()), scope);
    }

    /** Records the owner's start of monitoring (ADR-0025); refused while an earlier step is incomplete. */
    @PostMapping("/onboarding/monitoring-start")
    OnboardingResponse startMonitoring(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody MonitoringStartRequest request) {
        return OnboardingResponse.from(onboarding.startMonitoring(scope, request.expectedVersion()), scope);
    }

    record MonitoringStartRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Onboarding version seen; 0 if none")
            int expectedVersion) {
    }

    record MonitoringResponse(
            Instant startedAt,
            UUID startedBy,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Onboarding.AdsScope ads) {
    }

    record DraftRequest(Goal goal, Integer planningHorizonDays, RiskPreference riskPreference, TaxBasis taxBasis,
            List<Priority> priorities,
            @Schema(description = "Planned ad spend for the month, minor units of the store currency")
            Long plannedAdSpendMinor,
            @Schema(example = "2026-12") String plannedAdSpendPeriod,
            String note,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Draft revision seen; 0 for a new draft")
            int expectedRevision) {
    }

    record ApproveRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int expectedRevision) {
    }

    record PlannedAdSpendResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String period) {
    }

    record ProfileResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status,
            Goal goal,
            Integer planningHorizonDays,
            RiskPreference riskPreference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TaxBasis taxBasis,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Priority> priorities,
            PlannedAdSpendResponse plannedAdSpend,
            String note,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int revision,
            Instant approvedAt,
            Instant effectiveFrom,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MissingField> missingFields) {

        static ProfileResponse from(BusinessProfile profile) {
            if (profile == null) {
                return null;
            }
            PlannedAdSpendResponse spend = profile.plannedAdSpend() == null ? null : new PlannedAdSpendResponse(
                    profile.plannedAdSpend().amountMinor(), profile.plannedAdSpend().currency(),
                    profile.plannedAdSpend().period());
            return new ProfileResponse(profile.id(), profile.reference(), profile.version(), profile.status(),
                    profile.goal(), profile.planningHorizonDays(), profile.riskPreference(), profile.taxBasis(),
                    profile.priorities(), spend, profile.note(), profile.revision(), profile.approvedAt(),
                    profile.effectiveFrom(), profile.missingFields());
        }
    }

    record ProfileStateResponse(ProfileResponse approved, ProfileResponse draft,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canEdit) {
    }

    record OnboardingRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Step currentStep,
            Set<Acknowledgement> acknowledgements,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Version seen; 0 if none") int expectedVersion) {
    }

    record StepResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Step step,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean complete,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> blockers) {
    }

    record DomainResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Onboarding.Domain domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Onboarding.Readiness readiness,
            String detail) {
    }

    record OnboardingResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Step currentStep,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Step nextStep,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StepResponse> steps,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DomainResponse> domains,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Acknowledgement> acknowledgements,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MonitoringResponse monitoring,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canEdit) {

        static OnboardingResponse from(OnboardingState state, TrustedStoreScope scope) {
            return new OnboardingResponse(state.currentStep(), state.nextStep(),
                    state.steps().stream().map(s -> new StepResponse(s.step(), s.complete(), s.blockers())).toList(),
                    state.domains().stream().map((DomainState d) -> new DomainResponse(d.domain(), d.readiness(),
                            d.detail())).toList(),
                    state.acknowledgements().stream().sorted().toList(), state.version(),
                    new MonitoringResponse(state.monitoring().startedAt(), state.monitoring().startedBy(),
                            state.monitoring().ads()),
                    scope.has(StorePermission.STORE_MANAGE));
        }
    }
}
