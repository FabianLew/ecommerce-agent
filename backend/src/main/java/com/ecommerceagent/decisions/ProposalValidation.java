package com.ecommerceagent.decisions;

import java.util.List;

/** Result of validating a proposal document: the parsed proposal when valid, otherwise its errors. */
public record ProposalValidation(DecisionProposal proposal, List<ProposalError> errors) {

    public ProposalValidation {
        errors = List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public List<String> codes() {
        return errors.stream().map(ProposalError::code).distinct().toList();
    }
}
