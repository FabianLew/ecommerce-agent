Assess episode {{opportunityId}} for product variant {{variantId}} on snapshot {{snapshotId}} (as of {{asOf}}).
The risk threshold is {{thresholdDays}} days of cover. Use the skill inventory-guardian@1.0.0: check the forecast,
the replenishment, the mappings, the delivery state of the ads you consider, and earlier actions. Choose only an
action from the schema and the process scope. Briefly explain how the recommendation follows from the evidence and
state the uncertainties. The backend assigns identifiers and evaluates the permission.

Return one JSON object matching this schema (the `recommendation` of DecisionProposal v1):

{{schema}}

Allowed reason codes: {{reasonCodes}}
Allowed missing requirements: {{missingRequirements}}
Allowed monitor conditions: {{monitorConditions}}
A monitor review must be between {{minReview}} and {{maxReview}} after now.
