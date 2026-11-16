# Usability test script (R14)

A short moderated test of the redesigned pilot UI (plan section 10). It checks whether a store owner who has not
seen the product understands what the operator is doing and can make the approval decision with confidence.

## Setup

- **Participants:** 2–3 store owners or people outside IT. Nobody who worked on the product.
- **Length:** 30 minutes each: 3 minutes intro, 22 minutes tasks, 5 minutes wrap-up.
- **Environment:** the local demo stack after the Inventory Guardian scenario (store "Sklep B", one decision
  waiting). Language of the participant's choice (EN or PL). Desktop browser at 1440 px; task 8 on a phone or at
  390 px.
- **Roles:** one moderator, one note-taker. Screen and voice recording only with the participant's consent.
- **Reset between sessions:** `docker compose down -v`, start the stack, run the scenario spec with
  `E2E_SCENARIO=1`.
- **Before task 4b:** the moderator switches on "Stop changes to ads" in Settings in another window.

## Moderator rules

- Ask the participant to think aloud. Do not explain the interface, do not point, do not name buttons.
- If a participant is stuck for 2 minutes, note it as a failure and move on with a neutral hint ("Where would you
  look next?").
- Use the participant's words when asking follow-up questions, not the product's terms.

## Intro (read aloud)

"You run an online shop. This tool watches your store and your ads and suggests what to do. It never changes
anything without your approval. We are testing the tool, not you. There are no wrong answers. Please say what you
are thinking as you go."

## Tasks

Start every task from the Overview unless stated otherwise.

| # | Task (say to the participant) | Success criteria | Screen |
| --- | --- | --- | --- |
| 1 | "Is the tool watching your store right now? How do you know?" | Says monitoring is not started yet (or since when it is on) and points to the status band or the setup callout | Overview |
| 2 | "Is there anything you need to do today?" | Names the waiting decision about the serum and the next setup step | Overview |
| 3 | Open the waiting decision. "What is the problem, why does it matter, and what exactly will happen if you approve?" | Names the product, that stock runs out in about 2.6 days, that only the one dedicated ad is paused, and that the catalog ad and budgets are not changed | Decision Detail |
| 4a | "Would you approve it? What would stop you?" | Gives a reason based on what the page shows; notices that approval is not possible yet and why | Decision Detail |
| 4b | (Changes stopped) "Why can't you approve now?" | Points to the stop banner or the reason in the decision panel | Decision Detail |
| 5 | "If you had approved, where would you check that the pause really happened?" | Finds the execution section on the decision or the Activity page | Decision Detail, Activity |
| 6 | "What is the next step to finish setting up the store, and why is it needed?" | Opens Store setup, names "Connect advertising" and gives the reason from the panel | Store setup |
| 7 | "Which data sources are connected, and which need attention?" | WooCommerce connected, Meta Ads not connected | Integrations |
| 8 | (Phone) "Open the waiting decision and find the button to decide." | Reaches the decision and the action bar without help | Decisions, mobile |

## Measures

For each task the note-taker records:

- success (yes / with hint / no);
- time to the first correct answer;
- wrong turns (pages opened that do not help);
- the words the participant uses for things (case, decision, approval, pause, setup, data source).

After task 4a: "How sure are you about your decision, from 1 (not at all) to 5 (completely)?"

## Wrap-up questions

1. "What was the most confusing moment?"
2. "Was anything missing that you would need before approving a change to your ads?"
3. "How would you describe this tool to another shop owner in one sentence?"

## After the sessions

- A short findings report: task success table, the confidence ratings, the words people used (fed back into the
  terminology table in the frontend README) and up to five prioritized problems.
- Fixes go into small follow-up PRs. Nothing in the safety invariants (plan section 7.2) is changed because of a
  usability finding without an explicit owner decision.
