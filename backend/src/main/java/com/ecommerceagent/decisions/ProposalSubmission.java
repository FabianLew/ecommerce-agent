package com.ecommerceagent.decisions;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of submitting a recommendation: either a stored proposal, or the id of the recorded rejected attempt
 * with its errors. A rejected attempt is never a proposal (IG-10).
 */
public record ProposalSubmission(StoredProposal proposal, UUID rejectionId, List<ProposalError> errors) {

    public ProposalSubmission {
        errors = List.copyOf(errors);
    }

    public boolean accepted() {
        return proposal != null;
    }

    public List<String> codes() {
        return errors.stream().map(ProposalError::code).distinct().toList();
    }
}
