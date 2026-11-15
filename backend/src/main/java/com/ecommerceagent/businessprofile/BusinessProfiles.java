package com.ecommerceagent.businessprofile;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.businessprofile.ProfileEnums.Status;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The store's business profile (ADR-0018): one editable draft, one approved version, and the superseded history.
 * Editing and approving need {@code STORE_MANAGE}; approval re-checks the membership and needs a person.
 */
@Service
public class BusinessProfiles {

    private static final String RESOURCE = "store_business_profile";

    private final BusinessProfileRepository repository;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final Clock clock;

    BusinessProfiles(BusinessProfileRepository repository, StoreAccess storeAccess, AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.clock = clock;
    }

    public record ProfileState(BusinessProfile approved, BusinessProfile draft) {
    }

    public ProfileState state(TrustedStoreScope scope) {
        return new ProfileState(repository.withStatus(scope, Status.APPROVED).orElse(null),
                repository.withStatus(scope, Status.DRAFT).orElse(null));
    }

    public Optional<BusinessProfile> approved(TrustedStoreScope scope) {
        return repository.withStatus(scope, Status.APPROVED);
    }

    public List<BusinessProfile> history(TrustedStoreScope scope) {
        return repository.history(scope);
    }

    /**
     * Creates or updates the draft. {@code expectedRevision} is the draft revision the user saw, or 0 to start a
     * draft (from the approved version, if any).
     */
    @Transactional
    public BusinessProfile saveDraft(TrustedStoreScope scope, ProfileDraftInput input, int expectedRevision) {
        scope.require(StorePermission.STORE_MANAGE);
        validate(input);
        Instant now = clock.instant();
        String currency = repository.storeCurrency(scope);
        Optional<BusinessProfile> draft = repository.lockDraft(scope);
        if (draft.isEmpty()) {
            if (expectedRevision != 0) {
                throw conflict();
            }
            UUID id = UUID.randomUUID();
            repository.insertDraft(scope, id, repository.nextVersion(scope), input, currency,
                    scope.userId().orElse(null), now);
        } else {
            if (draft.get().revision() != expectedRevision) {
                throw conflict();
            }
            repository.updateDraft(scope, draft.get().id(), input, currency, now);
        }
        BusinessProfile saved = repository.withStatus(scope, Status.DRAFT).orElseThrow();
        audit.record(AuditEntry.inScope(scope, "BUSINESS_PROFILE_DRAFT_SAVED", RESOURCE, saved.id().toString(),
                Map.of("version", Integer.toString(saved.version()), "revision", Integer.toString(saved.revision()))));
        return saved;
    }

    /** Approves the draft the user saw; the previous approved version becomes SUPERSEDED. */
    @Transactional
    public BusinessProfile approve(TrustedStoreScope scope, int expectedRevision) {
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        UUID approver = fresh.userId().orElseThrow(() -> new BusinessProfileException(
                BusinessProfileException.Reason.INVALID, "approver", "PERSON_REQUIRED",
                "A profile is approved by a person"));
        BusinessProfile draft = repository.lockDraft(fresh).orElseThrow(() -> new BusinessProfileException(
                BusinessProfileException.Reason.NOT_FOUND, null, "NO_DRAFT", "There is no draft to approve"));
        if (draft.revision() != expectedRevision) {
            throw conflict();
        }
        if (draft.goal() == null) {
            throw invalid("goal", "REQUIRED", "Choose the store's goal before approving");
        }
        if (draft.planningHorizonDays() == null) {
            throw invalid("planningHorizonDays", "REQUIRED", "Set the planning horizon before approving");
        }
        if (draft.riskPreference() == null) {
            throw invalid("riskPreference", "REQUIRED", "Choose the risk preference before approving");
        }
        Instant now = clock.instant();
        repository.supersedeApproved(fresh, now);
        repository.approve(fresh, draft.id(), approver, now);
        BusinessProfile approved = repository.withStatus(fresh, Status.APPROVED).orElseThrow();
        audit.record(AuditEntry.inScope(fresh, "BUSINESS_PROFILE_APPROVED", RESOURCE, approved.id().toString(),
                Map.of("version", Integer.toString(approved.version()), "goal", approved.goal().name(),
                        "riskPreference", approved.riskPreference().name())));
        return approved;
    }

    private static void validate(ProfileDraftInput input) {
        if (input == null) {
            throw invalid("profile", "REQUIRED", "The profile is required");
        }
        Integer horizon = input.planningHorizonDays();
        if (horizon != null && (horizon < 7 || horizon > 365)) {
            throw invalid("planningHorizonDays", "OUT_OF_RANGE", "The horizon is 7 to 365 days");
        }
        if (input.plannedAdSpendMinor() != null) {
            if (input.plannedAdSpendMinor() < 0 || input.plannedAdSpendMinor() > 100_000_000_000L) {
                throw invalid("plannedAdSpendMinor", "OUT_OF_RANGE", "The planned spend is not valid");
            }
            if (input.plannedAdSpendPeriod() == null
                    || !input.plannedAdSpendPeriod().matches("[0-9]{4}-(0[1-9]|1[0-2])")) {
                throw invalid("plannedAdSpendPeriod", "PERIOD_FORMAT", "The period is a month, YYYY-MM");
            }
        }
        if (input.note() != null && input.note().length() > 2000) {
            throw invalid("note", "TOO_LONG", "At most 2000 characters");
        }
    }

    private static BusinessProfileException invalid(String field, String code, String message) {
        return new BusinessProfileException(BusinessProfileException.Reason.INVALID, field, code, message);
    }

    private static BusinessProfileException conflict() {
        return new BusinessProfileException(BusinessProfileException.Reason.VERSION_CONFLICT, "expectedRevision",
                "PROFILE_CHANGED", "The draft changed since it was loaded");
    }
}
