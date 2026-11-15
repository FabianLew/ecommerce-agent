# Ad mapping rules

- `SINGLE_VARIANT` with one variant: the ad promotes only this variant. It can be paused when the mapping is
  `VERIFIED`, current for the ad's creative, and the ad's own and effective status are both `ACTIVE`.
- `MULTI_PRODUCT` (catalog, shared adset): the ad also sells other products. It is excluded from a pause and is
  listed with the reason `MULTI_PRODUCT_AD_EXCLUDED`.
- An unverified mapping, or one made for an older creative, is not a basis for a pause (`MAPPING_UNVERIFIED`,
  `CREATIVE_CHANGED`); the decision goes to a person (`ESCALATE`).
- If the dedicated ads are already paused or not delivering, there is nothing to pause (`AD_NOT_ACTIVE`).
