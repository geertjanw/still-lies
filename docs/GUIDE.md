# Still-Lies: Talk Guide

## The Problem

A support agent retrieves the right refund policy (14 days) and then tells customers they have 30 days. Every conventional check is green:
- HTTP 200 ✓
- Low latency ✓  
- Tool called (lookup_policy) ✓
- Question answered ✓

Yet the agent lies. This demo shows how to catch it.

## The Setup

The repository contains three implementations of the same agent:

**`--agent.variant=broken`** (the lie)
- Looks up the 14-day policy correctly
- Ignores it and tells customers 30 days
- Passes all conventional checks

**`--agent.variant=good`** (the fix)
- Looks up the 14-day policy
- Tells customers the truth
- Also passes all conventional checks

**`--agent.variant=broken-plus`** (the "fixed" lie)
- Fixed the 30-day bug  
- But introduced two new problems the gate caught:
  - Wrong policy for cancellations
  - Padding answers with claims the policy doesn't make
  - Flatly saying no when eligibility is unclear (should ask for missing details)

## The Loop

### 1. Evals in Dash0

Before sending any traffic, create the evals. Dash0 Evals don't backfill — spans that arrive before an eval exists are never judged.

```bash
java -jar cli/target/still-lies.jar dash0-evals
```

This creates three evals: groundedness, faithfulness, and a regex check for the 30-day lie.

### 2. Reproduce the Incident

Start the broken agent in one terminal, sending traces to Dash0:

```bash
java -jar agent/target/support-agent.jar --spring.profiles.active=dash0 --agent.variant=broken
```

Send traffic in another:

```bash
java -jar cli/target/still-lies.jar traffic
```

After a few minutes, open a `chat` span in Dash0. The **Evals** tab shows:
- Groundedness: **FAIL** on refund questions (the 30-day lie)
- Tool Correctness: **PASS** (it called lookup_policy)
- Latency: **PASS** (under 10 seconds)
- Regex: **FAIL** (detected "30-day")

Every other metric is green. The conventional checks are all green. Yet the gate will fail it.

### 3. Export and Review

```bash
java -jar cli/target/still-lies.jar export
java -jar cli/target/still-lies.jar report
java -jar cli/target/still-lies.jar cases
```

`report` shows the groundedness fail rate by topic: billing fails 100%, the rest pass.

`cases` writes `data/to_review.csv`. The expected answer is already filled in for each question (the reviewed, correct response). Save it as `data/reviewed.csv`.

### 4. Build the Golden Dataset

```bash
java -jar cli/target/still-lies.jar dataset
```

Writes `evals/billing-regressions.jsonl` — the golden dataset for this domain. Commit it.

### 5. Offline Evals

Prove the dataset catches the bug. With the broken agent still running:

```bash
java -jar cli/target/still-lies.jar eval
```

The billing cases fail. Then restart without `--agent.variant=broken` (the fixed agent) and save the baseline:

```bash
java -jar cli/target/still-lies.jar eval-baseline
java -jar cli/target/still-lies.jar flaky
```

`flaky` runs the suite five times. No cases should flip between runs. Commit `.evals/baselines/`.

### 6. The Gate

This is what CI runs on every PR, push to main, and nightly:

```bash
java -jar cli/target/still-lies.jar gate
```

It exits 0 against the fixed agent and 1 against the broken one. The gate checks three things:

- **Per-case thresholds**: Every metric in `evals/billing.eval.yaml` has a `threshold`. One case below its threshold fails the gate.
- **Metric average**: If any metric's average across all cases is below `--fail-under 0.8`, the gate fails.
- **Regression**: If any metric dropped more than `tolerance: 0.05` below the stored baseline, the gate fails.

Good averages are not enough. One bad case fails the gate.

## What the Judge Sees

The gate uses **gpt-6.1-terra** as the judge (accuracy-focused, lowest hallucination rate in GPT-6; older accounts hit rate limits on gpt-4o).

The judge is passed three metrics:

### Groundedness (Threshold: 0.7)

**The rubric**: The expected output is a reviewed, correct answer. A factual claim is any statement about time limits, amounts, eligibility, what will happen, or what the customer can do. Judge only whether the output contradicts the expected output or gives the customer a different outcome.

- **1.0**: No claim contradicts the expected output, outcome is the same
- **0.5**: Minor detail conflicts but outcome is the same  
- **0.0**: Key claim contradicts (different time limit, amount, eligibility, or outcome)

This rubric *only* checks contradiction with the reviewed answer. Extra claims that go beyond the short expected answer are OK, as long as they don't conflict. That's what faithfulness checks.

### Faithfulness (Threshold: 0.65)

Are the answer's claims supported by the retrieved policy text? One unsupported claim in a three-claim answer is allowed (0.65 scores 1.0 if two are supported). The system prompt forbids extra advice or offers the policy doesn't state.

### Tool Correctness

Did the agent actually call `lookup_policy`? No judge needed, this is binary.

### Regex (Forbidden: "30-day")

The exact bug from the incident must never come back.

### Latency (Max: 10 seconds)

Quality is not bought with slowness. Two model calls must finish within 10 seconds.

## Three Bugs the Gate Found

After the initial fix of the 30-day lie, the gate revealed three more problems:

1. **Wrong policy for cancellations** (broken-plus variant)
   - The agent picked the refund policy for cancellation questions
   - Should use the cancellation policy instead
   - The `lookup_policy` tool description now clarifies which topic covers what

2. **Padding answers with false claims** (broken-plus variant)
   - The agent added claims the policy doesn't make
   - Groundedness scored OK (didn't contradict), but faithfulness failed at 0.50
   - The agent now checks the system prompt: no extra advice

3. **Flatly saying no when eligibility is unclear** (broken-plus variant)
   - Customer: "I bought it last month"
   - Agent: "No, you're not eligible"
   - But 14 days from "last month" might have passed — we don't know
   - The fix: explain the rule and ask for the missing detail instead of deciding
   - This is now in the system prompt

None of these would have been caught by conventional checks. All three were red flags in the eval run.

## For the Talk

### Show the Gate Going Green

Run the fixed agent locally:

```bash
java -jar agent/target/support-agent.jar &
sleep 3
java -jar cli/target/still-lies.jar gate
# Exits 0, all metrics pass
```

### Show the Gate Going Red (Demo Branch)

The `demo/broken-variant` branch switches the CI workflow to use `--agent.variant=broken`. Push it and watch the gate fail on stage in real time:

```bash
git push origin demo/broken-variant
gh run watch --repo geertjanw/still-lies
```

The gate will fail on:
- Groundedness 0.0 for every refund question
- The regex detecting "30-day"

Every other check: green. Yet the gate is red.

### The Rubric Update

Show the change in `evals/billing.eval.yaml`: from "how fully every factual claim agrees" to "does the output contradict or give a different outcome." This shift from coverage to contradiction is crucial — it lets correct answers that add true policy details pass groundedness, while catching lies about time limits.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Agent did not start` after running the fixed agent | Check that port 8080 is free. Kill any prior agent: `pkill -f "support-agent"` |
| `Could not transfer artifact` from Maven | Network issue on the repository. Retry the build: `mvn clean package -DskipTests` |
| `Missing OpenAI API key` | Set `OPENAI_API_KEY` in `.env` and ensure you have bought credits (free tier rates are too low) |
| Gate exits 2 (not 0 or 1) | Configuration error. Check that `.evals/baselines/initial.json` and `latest.json` exist and are committed |
| One case flips between runs | Run `java -jar cli/target/still-lies.jar flaky`. Flaky cases usually involve model temperature or context limits. Filter them from the golden dataset if they consistently flip |
