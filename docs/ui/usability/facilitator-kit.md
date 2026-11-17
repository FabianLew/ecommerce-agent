# Usability test: facilitator kit

Everything needed to run the sessions in [the test script](../usability-test-script.md) with 2–3 store owners. The
script says *what* to test; this kit says *how* to prepare, run and report. Read the
[cognitive walkthrough](cognitive-walkthrough-2026-11-17.md) first: it lists the problems to watch for.

## 1. Recruiting (a week before)

**Who to invite:**

- Owners or employees of small online shops, or people outside IT who buy online often.
- Not people who know the project.
- Two participants are the minimum and three are better.

**Screener (all should be "yes" for at least two participants):**

1. Do you sell online, or help someone who does?
2. Have you ever run or looked at paid ads (Facebook, Instagram, Google)?
3. Do you have 40 minutes on a laptop with a browser?

**Book:**

- 40 minutes per participant: 30 minutes for the session and 10 for reset and notes.
- At most 3 sessions in a row, because each reset takes about 10 minutes.

**Thank-you:** a small voucher or a thank-you note. Decide it before you invite.

## 2. Consent (read before the session; get a "yes" out loud or in writing)

"We are testing a new tool, not you. The session takes about 30 minutes. With your permission we record the
screen and your voice, only so that we can review our notes. The recording is not shared outside the team and is
deleted after the report. You can stop at any time without giving a reason. Is that okay?"

**Note:**

- If the participant says no to recording, take notes only.
- Store no personal data beyond a first name and the session date.

## 3. Equipment

- **Moderator laptop:** runs the demo stack (section 4) and shares the browser window. A remote session over a
  video call works: share only the browser window and give the participant remote control, or let them direct.
- **Phone for task 8:** a real phone cannot reach the laptop's `localhost`. Use the browser's device mode at
  390 × 844 (Chrome DevTools, toggle device toolbar, "iPhone 12 Pro") on the moderator laptop, and give the
  participant the mouse.
- **Second browser window** (a private window, signed in as the same owner) for switching "Stop changes" before
  task 4b without the participant seeing Settings.
- **Note-taker:** a second person with the observation sheet (section 7). If there is none, record the session
  and fill the sheet afterwards.

## 4. Starting the demo stack (once, about 15 minutes)

**Needs:** Docker, JDK 21 and Node 22 (`corepack enable`). The commands are the same as the CI job
"Frontend e2e"; run them from the repository root on the `pilot` branch with the redesign merged, or on the
redesign branch.

```bash
# 1. Local services (Postgres, Keycloak, WooCommerce with the seed)
cd infra && docker compose up -d --wait --wait-timeout 420 && cd ..

# 2. Backend API and worker with the e2e settings (shorter waits, same rules)
cd backend && ./gradlew bootJar
JAR=build/libs/ecommerce-agent-backend-0.1.0-SNAPSHOT.jar
E2E=--spring.config.additional-location=file:../infra/e2e/application-e2e.yml
nohup java -jar $JAR --spring.profiles.active=api,local $E2E > /tmp/api.log 2>&1 &
# wait until http://localhost:8080/actuator/health answers, then:
nohup java -jar $JAR --spring.profiles.active=worker,local $E2E > /tmp/worker.log 2>&1 &
cd ..

# 3. Frontend (production build)
cd frontend && cp -n .env.example .env.local && pnpm install --frozen-lockfile && pnpm build
nohup pnpm start > /tmp/web.log 2>&1 &
pnpm exec playwright install chromium   # first time only
```

**Safety:**

- The stack is local only.
- It calls no paid AI. The LLM agent is off, and the recommendation comes from the deterministic baseline.
- It never reaches a real Meta account: ad data comes from the local fixture, and the local policy mode is shadow
  mode, so an approval would not execute.

## 5. Preparing the demo state (before every session, about 5 minutes)

The participant starts from the "scenario state": store "Sklep B" connected, one low-stock decision waiting, and
monitoring not started.

```bash
cd frontend
E2E_SCENARIO=1 pnpm exec playwright test e2e/inventory-guardian-scenario.spec.ts --trace off
```

The spec must pass. It fails with "store B is already connected" when the stack is not fresh; then reset first
(section 6).

**Sign in for the participant:**

- Sign in as `owner-b` / `owner-b` at http://localhost:3000.
- Choose the language with the participant: the account menu has the switch.
- Open the Overview.
- Close every other tab.

**Before the participant arrives, check:**

- [ ] Overview shows "1 decision is waiting for you" and "Monitoring: Not started".
- [ ] No red "Changes are stopped" banner.
- [ ] The private window is signed in and has Settings open, scrolled to "Stop changes".
- [ ] Recording is ready, and the browser zoom is 100 %.

## 6. Reset between sessions (about 10 minutes)

The scenario needs a fresh store, so reset the whole stack. Participants must never see the previous person's
clicks.

```bash
pkill -f ecommerce-agent-backend            # stop API and worker
cd infra && docker compose down -v && docker compose up -d --wait --wait-timeout 420 && cd ..
# start API and worker again (section 4, step 2), then run the scenario (section 5)
```

Keep the frontend running; it has no state of its own.

## 7. Observation sheet (one per participant)

Participant: ______  Date: ______  Language: EN / PL  Moderator: ______  Note-taker: ______

| # | Success (yes / hint / no) | Time to first correct answer | Wrong turns (pages opened) | Participant's words | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4a | | | | | Confidence 1–5: ___ |
| 4b | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |

**Watch especially for** (from the walkthrough):

- W-1: does the participant ask how the tool knows about the ads?
- W-2: in 4b, do they find the banner, and do they expect the reason in the decision panel?
- W-4: do they open Evidence, and does the "not confirmed" mapping worry them?
- W-5: do they use or stumble over "episode", "cited", "technical details" or "L1"?
- W-6: how do they read "Ad pauses: Off"?

**Wrap-up answers:**

1. Most confusing moment: ______
2. Missing before approving a change to ads: ______
3. One-sentence description: ______

## 8. Results report template

Write the report in English under `docs/ui/usability/results-YYYY-MM-DD.md`.

```markdown
# Usability test results (YYYY-MM-DD)

## Sessions
- Participants: N (short profile, no names), languages, remote or in person.

## Task success
| # | P1 | P2 | P3 | Notes |
| 1 | yes / hint / no | … | … | |
…

## Confidence after task 4a
P1 _/5, P2 _/5, P3 _/5. Median _.

## Words people used
| Product term | Participants said |
| decision | … |
| approve | … |
| pause | … |
| setup | … |
| data source | … |

## Walkthrough predictions
| Finding | Confirmed / not seen / contradicted | Evidence (quote or observation) |
| W-1 … W-10 | | |

## Top problems (max. 5, prioritized)
1. Problem, how many participants, severity, proposed fix (frontend only / needs API proposal / needs owner decision).

## Decisions needed
- Anything that touches safety invariants (plan section 7.2) goes here, not into a fix.
```

## 9. After the sessions

- Delete the recordings once the report is written.
- Fixes go into one small frontend PR, ordered by the report's priorities.
- An API change, for example returning every approval blocker (W-2), is a separate proposal.
- A usability finding never changes the safety invariants without an explicit owner decision.
