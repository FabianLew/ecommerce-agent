# Cognitive walkthrough of the usability tasks (2026-11-17)

> **Simulated, not a usability test.** This is an expert walkthrough of the eight tasks in
> [the test script](../usability-test-script.md), done by the implementer on the live local demo stack. Nobody
> outside the project took part. It predicts problems and checks that the test can run as written; it does not
> replace sessions with 2–3 real store owners. Each finding says whether real sessions should confirm it.

## Method

- **Stack:** fresh local stack (`docker compose down -v`), then the Inventory Guardian scenario spec
  (`E2E_SCENARIO=1`, passed) on the redesign branch at `77c5775`. Store "Sklep B", user owner-b, one decision
  waiting.
- **Coverage:** every task screen captured at 1440 px in English and Polish, plus the decision at 390 px.
- **Questions asked at each step (Wharton et al.):**
  - Will a store owner try to reach this goal?
  - Will they see the control or the information?
  - Will they connect it to their goal?
  - Will they understand the feedback?
- **Two extra states,** reached through the UI and needed by task 4b:
  - **State B:** Meta skipped, business profile approved, monitoring started.
  - **State C:** ad pauses allowed. The "Stop changes" switch was turned on in both states.

## Predicted task outcomes

| # | Task | Prediction | Main reason |
| --- | --- | --- | --- |
| 1 | Is the tool watching? | Pass, with a question | "Monitoring: Not started" is clear, but a waiting decision about an ad makes people ask how it found anything (W-1) |
| 2 | Anything to do today? | Pass | "Needs attention" lists the decision and the next setup step first |
| 3 | Problem, impact, what happens | Pass | Facts row, "Why it matters" and the scope sentence ("Only this ad is paused …") answer it directly. The evidence list can mislead (W-4) |
| 4a | Would you approve? | Pass with confusion | Approval is blocked and the reason is shown, but the reason changes as setup moves on, and in the demo approval is never possible (W-3) |
| 4b | Why can't you approve (changes stopped)? | **Cannot pass as written** | The decision panel keeps showing an earlier blocker; the stop is only visible in the banner (W-2) |
| 5 | Where to verify the pause? | Pass with a hint | Activity has the case events. Setup events ("The webhook secret was saved") add noise (W-9) |
| 6 | Next setup step | Pass | Store setup selects step 2 and explains why and what is checked |
| 7 | Which sources are connected? | Pass | Catalog shows WooCommerce "Connected", Meta Ads "Not connected". This contradicts the ad data in the decision (W-1) |
| 8 | Phone decision | Pass | The sticky bar says "Approval is not possible now" with "See why" |

## Findings

Severity is the predicted effect on a store owner: **High** means a task fails or trust drops, **Medium** means
confusion or a wrong turn, and **Low** means friction.

### W-1 High: the demo state contradicts itself

**What a participant sees:**

- Monitoring is "Not started".
- Integrations and Store setup show Meta Ads as "Not connected", and setup readiness shows "Ads: Not started".
- A decision talks about a running Meta ad and cites the ad's state.
- Readiness also says "Ad mappings: Ready, 1 confirmed single-variant ads". This also has a plural bug.

**Why:** in the local demo the ad data comes from the fixture file, not from a connection.

**Expected effect:** a participant asks "how does it know about my ads?", which spoils tasks 1, 3 and 7.

**Proposal:**

- For the sessions, the moderator says in the intro that the store and its ads are simulated (see the updated
  script).
- The "1 … ads" plural is a frontend copy fix.
- A demo state with Meta connected through the local Graph stub is possible, but it is a larger change to the demo
  setup. Do it only if the sessions show this confusion.

**Confirm in real sessions:** yes.

### W-2 High: the decision panel shows one blocker, and a stop is never it in the demo

**Behaviour:** the API returns one `blockedReason`, the first cap in the backend's order:

1. monitoring not started;
2. store permission;
3. shadow mode;
4. Meta connection;
5. mutation stop.

In both the scenario state and state B, the panel kept its earlier reason after "Stop changes":

- scenario state: "Monitoring has not been started";
- state B: "Ad pauses are not allowed".

The stop showed only in the red banner at the top. The banner is very visible on desktop and on the phone, so many
participants will still find it. But the task's expected answer ("the reason in the decision panel") cannot happen.

**Proposal:**

- **Frontend only, existing data:** while a stop holds (the shell already reads it for the banner), the decision
  panel adds the line "Changes are stopped for this store" under the blocker. This does not change safety
  semantics; it only shows a second reason that already applies.
- **API option, as a separate proposal:** return every cap, not only the first.
- **Script:** task 4b now accepts the banner as the success path.

**Confirm in real sessions:** partly. The script change is needed either way.

### W-3 Medium: approval is never possible in the local demo

**Behaviour:**

- The local stack runs in shadow mode with no live Meta account, so approval stays blocked whatever the
  participant does.
- In state C the store setting was changed to "Approval required" through the settings form, but the reason stayed
  "Ad pauses are not allowed". This was not investigated further; even when that clears, shadow mode is next.

**Expected effect:** a participant who tries to get to "Approve" is sent to Settings and then to another blocker.

**Proposal:**

- In the script, task 4a asks "Would you approve it if you could?" and ends once the participant has given a reason
  and noticed the block.
- The moderator does not let participants change settings.

**Confirm in real sessions:** no. This is a property of the demo stack.

### W-4 Medium: the evidence list does not say which ad a mapping belongs to

**Behaviour:** Evidence lists two "Ad mapping" rows without an ad name. The first one reads "Promotes several
products · not confirmed", right before "Ad state: Serum 30 ml – reklama dedykowana · running". It reads as if the
serum ad were unconfirmed, which contradicts the recommendation and the passed safety check.

**Proposal:** a frontend fix. The mapping payload already has the `adId`, so name the ad on the mapping row, or
group mapping and state per ad.

**Confirm in real sessions:** yes. Watch whether participants open Evidence at all.

### W-5 Medium: jargon on Decision Detail

**Terms that will not mean anything to a store owner:**

- "The target belongs to this store and this **episode**";
- "**Cited by the proposal**", repeated 8 times;
- "**Technical details**", repeated 9 times;
- "**Autonomy level** … **L1**".

**Proposal:**

- Replace "episode" with "case".
- Show "cited" once as a small legend or icon.
- Move the per-item technical details behind one toggle for the whole section.
- Explain L1 in words only: "A person approves every action".

**Confirm in real sessions:** yes, through the vocabulary notes.

### W-6 Medium: "Ad pauses: Off" next to a proposal to pause an ad

**Behaviour:** the status band and the sidebar say "Ad pauses: Off" while the top item in "Needs attention"
proposes pausing an ad. "Off" can read as "this feature does not work" or "nothing will be paused".

**Proposal:** copy such as "Ad pauses: need your permission" or "not allowed yet".

**Confirm in real sessions:** yes.

### W-7 Low: the blocker text speaks about the owner in the third person

"A store manager can allow them in the settings" is shown to the owner, who is that manager.

**Proposal:** "You can allow them in Settings", with a link, when the user has the permission.

### W-8 Low: duplicated "No data"

The Meta spend and contribution margin cards show "No data" as the value and again as the badge.

**Proposal:** keep one of them, and keep the explanation line.

### W-9 Low: setup noise in Activity

Activity mixes the decision's history with setup events:

- "The API key was saved";
- "A connection check finished";
- "An integration connection was created".

For task 5 the owner has to skim past them. The Detection, Recommendation and Action filters exist but are easy to
miss.

**Proposal:** observe first. If participants get lost, open Activity from a decision already filtered to that case.

### W-10 Low: "Waiting" does not say for whom

The badge "Waiting" ("Oczekuje") sits next to "Decide in 24 hours". "Waiting for you" is clearer.

**Confirm in real sessions:** yes, through the vocabulary notes.

## What works well (keep)

- **Overview:** the order of status band, then "Needs attention", then pulse answers tasks 1 and 2 above the fold
  at 1440 px.
- **Decision Detail:**
  - The four fact tiles, with the highlighted "Stock lasts 2.6 days", give the problem at a glance.
  - The scope sentence and "Ads that will not be paused" answer "what exactly happens".
- **Safety:**
  - The stop banner is prominent at both 1440 and 390 px.
  - The phone action bar never offers an approve button that cannot work.
- **Store setup:** it shows one clear next step, with "why it matters" and "what we check".
- **Polish copy** reads naturally. Only "epizod" sounds technical (W-5).

## Recommended order

1. Run the real sessions with the updated script, including the W-1 intro line and the new task 4a and 4b wording.
   The script now matches the demo's real behaviour.
2. After the sessions, fix whatever they confirm in one small frontend PR. Likely candidates are W-2 (stop line in
   the panel), W-4, W-5, W-6 and W-7; none of them needs an API change.
3. The API proposal for all blockers (W-2) and a Meta-connected demo state (W-1) only if the sessions show they are
   needed.

## Evidence

Screenshots and the captured page text for every task are in
`/mnt/project-files/ui-redesign/usability/walkthrough-screens/`:

- `en/`, `pl/`: the scenario state;
- `state-b/`: setup finished, monitoring started;
- `state-c/`: ad pauses allowed, with and without the stop.
