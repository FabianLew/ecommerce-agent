# AWS cost estimate: staging and production (ADR-0023 point 9)

- Status: future option, not in use. On 2026-11-15 the owner chose a single EU VPS for the demo and the first pilot
  (ADR-0028, [VPS estimate](vps-cost-estimate.md)). Nothing here is applied without a new approval.
- Date: 2026-11-15
- Region: `eu-central-1` (Frankfurt), on-demand prices in USD, excluding VAT, from the public price lists as
  remembered when this was written. **Every line must be checked in the AWS Pricing Calculator before approval**;
  treat the totals as ±25 %.
- Shape: `infra/terraform` as written (two AZs, no NAT gateway, single-AZ database, 730 hours a month).

## Production

| Item | Size | Basis | Per month |
| --- | --- | --- | --- |
| Fargate `api` | 0.5 vCPU, 1 GB (incl. collector) | 0.5 × $0.0466 + 1 × $0.0051 per hour | ~$21 |
| Fargate `worker` | 0.5 vCPU, 1 GB (incl. collector) | same | ~$21 |
| Fargate `frontend` | 0.25 vCPU, 0.5 GB | | ~$10 |
| Fargate `keycloak` | 0.5 vCPU, 1 GB | | ~$21 |
| Fargate one-off tasks | migration per rollout, administration | minutes a month | <$1 |
| Application load balancer | 1, low traffic | $0.027/h + ~1 LCU | ~$26 |
| Public IPv4 addresses | 4 tasks + 2 for the load balancer | $0.005/h each | ~$22 |
| RDS PostgreSQL 17 | `db.t4g.small`, single-AZ, 20 GB gp3 | ~$0.036/h + $0.137/GB | ~$29 |
| RDS backups | PITR 14 days | about the database size, mostly within the free allocation | ~$1 |
| KMS | 3 keys + requests | $1 per key | ~$4 |
| Secrets Manager | 9 secrets incl. the RDS master | $0.40 each | ~$4 |
| CloudWatch | logs ~5 GB, Container Insights, metrics from the collector, alarms | | ~$20–35 |
| X-Ray | sampled traces (10 %) | | ~$1–5 |
| S3 + ECR | dumps of 30 days, images | | ~$2 |
| SES | notifications, a few thousand e-mails | $0.10 per 1 000 | <$1 |
| Data transfer out | small API and page traffic | | ~$2–5 |
| **Production total** | | | **~$185–210** |

## Staging

Same shape, but `db.t4g.micro` and Fargate Spot (about 70 % cheaper compute, tasks may be interrupted).

| Item | Per month |
| --- | --- |
| Fargate Spot, 4 services | ~$25 |
| Application load balancer | ~$24 |
| Public IPv4 addresses | ~$22 |
| RDS `db.t4g.micro`, 20 GB | ~$16 |
| KMS, Secrets Manager | ~$8 |
| CloudWatch, X-Ray | ~$10–15 |
| S3, ECR, SES, transfer | ~$3 |
| **Staging total** | **~$110–115** |

Staging can be scaled to zero tasks outside working hours (`desired_count = 0`), which leaves roughly the load
balancer, the addresses of the load balancer and the database: about $55 a month.

## Outside AWS or usage-based

| Item | Per month | Note |
| --- | --- | --- |
| Temporal Cloud, Frankfurt | from ~$100 | Plan minimum; the pilot's action volume should fit in it. Check whether two namespaces fit one plan. |
| Claude via Bedrock (production) | usage | Depends on the number of agent runs; the agent's cost alert (ADR-0024) and the usage pacing of ADR-0017 bound it. |
| Claude direct API (staging, synthetic data) | usage | Developer test runs only. |

## Total

| | Per month |
| --- | --- |
| Production | ~$185–210 |
| Staging | ~$110–115 (or ~$55 when stopped at night) |
| Temporal Cloud | ~$100+ |
| **Fixed total** | **~$400–425**, plus model usage |

## Options that change the estimate

| Option | Change | When |
| --- | --- | --- |
| Multi-AZ production database | +~$29 | Before the pilot leaves L1 shadow (owner's decision, ADR-0023) |
| NAT gateway per environment instead of public task IPs | +~$40 each, plus $0.052 per GB | If a stricter network is required |
| Second task of `api` for zero-downtime rollouts | +~$25 | After the pilot |
| Savings plan for Fargate (1 year) | −~20 % compute | After the shape is stable |
| One account per environment | no price change | Recommended for isolation |
