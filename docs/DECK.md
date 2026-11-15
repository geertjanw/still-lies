# When Your LLM Passes Every Check And Still Lies — Slide Deck

## Slide 1: Title

**When Your LLM Passes Every Check And Still Lies**

- The tale of the 30-day refund lie
- And how an eval gate caught three more bugs

---

## Slide 2: The Setup

```
Agent retrieves policy: "Refund within 14 days"
Agent tells customer:   "You have 30 days"

Health check:     ✓ 200 OK
Latency:          ✓ <1s
Tool call:        ✓ lookup_policy called
Answer:           ✓ Question answered
Rate limit:       ✓ Normal
Errors:           ✓ None

Status: All green 🟢
```

Yet the agent lies.

---

## Slide 3: Why Conventional Checks Miss It

Conventional observability sees:
- The request succeeded
- The response came back fast
- The tool was called
- No exceptions were thrown

But it never asks: **Is the answer true?**

For LLMs, truth is what matters. Everything else is hygiene.

---

## Slide 4: The Demo Loop

```
Broken agent ──→ Evals in Dash0 ──→ Export to CSV ──→ Dataset
                                          ↓
                                   Review in CSV
                                        ↓
Gate catches the lie ←───── Offline eval ←─── JSON-L golden dataset
```

Three tools:
- **Dash0**: Span-level eval in production (LLM-as-judge on GenAI spans)
- **DuckDB**: SQL over exported spans, slice by topic
- **harness-evals**: Offline regression suite in CI

---

## Slide 5: The Eval Rubric (Groundedness)

**Old rubric (gpt-4o)**:  
"Score from 0.0 to 1.0 how **fully** every factual claim in the output agrees with the expected output."

Result: correct answers that added true policy details scored 0.50 (not green).

**New rubric (gpt-6.1-terra)**:  
"Judge only whether the output **contradicts** the expected output or gives a **different outcome**."

Result: correct answers that add detail pass 1.0. Only lies about time limits, amounts, or eligibility fail.

---

## Slide 6: The Rubric in Action

| Case | Expected | Agent Says | Groundedness | Why |
|------|----------|------------|---|---|
| Refund within 14d | "14 days" | "14 days" | 1.0 | ✓ |
| Refund within 14d | "14 days" | "30 days" | 0.0 | Lie |
| Refund within 14d | "14 days" | "14 days. After that, contact support." | 1.0 | Adds detail, no contradiction |
| Refund within 14d | "14 days" | "14 days, includes shipping" | 0.5 | Detail conflicts (shipping not mentioned) |

---

## Slide 7: Three Bugs the Gate Found

After we "fixed" the 30-day lie, the gate found three more:

### Bug 1: Wrong Policy
Agent picks refund policy for cancellation questions → should use cancellation policy

### Bug 2: False Claims  
Agent adds advice the policy doesn't make → faithfulness fails at 0.50

### Bug 3: Guessing When Uncertain
Customer: "I bought it last month"  
Agent: "No, ineligible" ← wrong, could be eligible  
Fix: "The rule is 14 days. When did you buy it?"

**None of these show up in conventional checks.** All three were red flags in the eval run.

---

## Slide 8: The Gate in CI

```yaml
metrics:
  - groundedness:  threshold 0.7
  - faithfulness:  threshold 0.65
  - tool_correct:  binary (called lookup_policy?)
  - regex:         forbidden "30-day"
  - latency:       max 10s

fail_under:        0.8 (average)
tolerance:         0.05 (vs baseline)
```

Exits 0 (pass) only if:
- Every case is above its threshold
- All metrics average ≥ 0.8
- No metric dropped >5% from baseline

**One bad case fails the gate.** Good averages aren't enough.

---

## Slide 9: The Judge

**gpt-6.1-terra** (not gpt-4o)

- Accuracy-focused, lowest hallucination in GPT-6 family
- gpt-4o has 3 RPM for new accounts (too slow)
- Sees: expected answer + agent's answer + tool context
- Judges: does it contradict? Does it mislead?

---

## Slide 10: Why This Matters

**The lie passed because:**
- The agent called the tool
- The tool returned the right data
- The response was fast
- No exceptions were thrown

**The lie failed because:**
- The eval explicitly asks: "Is this true?"
- The judge has the ground truth (reviewed answer)
- The gate enforces it on every PR and nightly

---

## Slide 11: For Your Stack

What you need to gate LLM quality:

1. **A labeling step** (your team reviews answers, marks right/wrong)
2. **A dataset** (export failed spans, tag them, build JSON-L)
3. **A judge** (LLM-as-judge with a rubric, gpt-6.1-terra for accuracy)
4. **A gate** (harness-evals in CI, one bad case fails)

Cost: ~10 USD per gate run (100 questions × 5 metrics × 2 API calls).  
Time to catch a bug: minutes in CI, not days in production.

---

## Slide 12: Demo: The Gate Goes Red

**Live on stage:**

```bash
git push origin demo/broken-variant
gh run watch --repo geertjanw/still-lies
```

The broken agent:
- Calls the tool ✓
- Answers the question ✓
- Finishes in <1s ✓
- Returns HTTP 200 ✓

Gate:  
🔴 **FAIL** – groundedness 0.0, regex "30-day" detected

---

## Slide 13: Demo: The Gate Goes Green

```bash
git push origin main
gh run watch --repo geertjanw/still-lies
```

The fixed agent:
- Calls the tool ✓
- Answers the question ✓
- Tells the truth ✓

Gate:  
🟢 **PASS** – all metrics 1.0 or above threshold

---

## Slide 14: The Rubric Update

**Before** (gpt-4o, coverage-based):  
"Score how **fully** every claim agrees"
→ Extra true details = 0.50 (not green)

**After** (gpt-6.1-terra, contradiction-based):  
"Judge only **contradictions**"
→ Extra true details = 1.0 (green)

The shift catches lies without penalizing helpful detail.

---

## Slide 15: Timeline

| Step | What | Time |
|---|---|---|
| 1 | Reproduce in production | ~5 min (with traffic) |
| 2 | Export + slice with DuckDB | ~1 min |
| 3 | Review and label | ~30 min (10 cases) |
| 4 | Build dataset | <1 min |
| 5 | Gate catches the lie | <2 min (offline eval) |

From "something's wrong" to "gate on every PR": ~40 minutes.

---

## Slide 16: Take-Aways

1. **LLM quality gates aren't optional.** Conventional checks miss truths.

2. **The judge needs ground truth.** LLM-as-judge without labels is hallucination.

3. **One bad case fails the gate.** Averages hide regressions.

4. **The rubric matters.** Shifting from coverage to contradiction let correct answers that add detail pass, while catching lies.

5. **This gate caught bugs the developer would have missed.** Same code, three different bugs, all found by the eval.

---

## Slide 17: Links

- Repository: `geertjanw/still-lies`
- Demo branch: `git push origin demo/broken-variant`
- Eval config: `evals/billing.eval.yaml`
- Baseline: `.evals/baselines/`
- Judge: gpt-6.1-terra (on OpenAI)
- Tool: [harness-evals](https://github.com/harness/harness-evals)

---

## Slide 18: Q&A

What questions do you have?

- Why gpt-6.1-terra and not gpt-4o?
- How do we label cases at scale?
- What if the gate is too strict?
- Can we use this for other domains (not just support)?
