# still-lies

The sample for the talk **When Your LLM Passes Every Check And Still Lies**.

A support agent retrieves the right refund policy (14 days) and then tells customers they have 30 days.
Every conventional check is green: HTTP 200, low latency, the tool was called, the question was answered.
This repo shows how to:

1. **Detect** it in production with Dash0 Evals (LLM-as-judge on OpenTelemetry GenAI spans).
2. **Bridge** the failures into a regression suite with DuckDB SQL.
3. **Gate** the next deploy in CI with the open-source [harness-evals](https://github.com/harness/harness-evals).
4. **Verify** the fix in production, and **automate** the loop with Temporal.

Everything is Java except harness-evals, which is a Python tool that the CLI runs with `uvx`.
There is no Python code and no Makefile in the repository.

## What you need

| Tool | Why |
|---|---|
| Java 21 and Maven 3.9+ | builds the agent and the CLI |
| [uv](https://docs.astral.sh/uv/) | runs harness-evals with `uvx`, no virtualenv |
| A Dash0 account and the `dash0` CLI | traces, evals, exports |
| An OpenAI API key | the agent's model, and the CI judge |
| Temporal CLI (optional) | `temporal server start-dev` for the incident workflow |

## Build and configure

```bash
cp .env.example .env                  # fill in your keys
mvn -q package -DskipTests            # agent/target/support-agent.jar and cli/target/still-lies.jar
alias still-lies='java -jar cli/target/still-lies.jar'
still-lies check
```

Run everything from the repository root: the agent and the CLI both read `.env` from there.
`still-lies help` lists every command.

## The loop

```bash
# 1. The three evals in Dash0, before any traffic (evals don't backfill)
still-lies dash0-evals

# 2. Terminal 1: the agent with the bug, sending traces to Dash0
java -jar agent/target/support-agent.jar --spring.profiles.active=dash0 --agent.variant=broken
#    Terminal 2: 51 requests. Then open a chat span in Dash0 and look at its Evals tab.
still-lies traffic

# 3. Export and slice with DuckDB
still-lies export
still-lies report                     # groundedness fail rate by topic: billing fails, the rest passes
still-lies cases                      # writes data/to_review.csv
#    Fill in the expected column, save as data/reviewed.csv

# 4. The golden dataset
still-lies dataset                    # writes evals/billing-regressions.jsonl: commit it

# 5. Offline evals: the dataset must catch the bug
still-lies eval                       # against the broken agent: billing fails
#    Terminal 1: restart without --agent.variant=broken (the fixed agent)
still-lies eval-baseline              # save the baseline (commit .evals/baselines/)
still-lies flaky                      # 5 runs; lists cases whose outcome changes

# 6. The gate: what CI runs
still-lies gate                       # exit 0 against the fixed agent, exit 1 against the broken one

# 7. Verify in production after the deploy
still-lies export --from now-6h
still-lies verify --deployed-at '2026-11-14 10:00:00+01'
```

## Without Dash0 or OpenAI

```bash
still-lies samples                    # bundled exports in samples/ instead of Dash0
still-lies report && still-lies cases && still-lies dataset

still-lies rehearsal-model            # terminal 1: a stand-in OpenAI model on :9999
java -jar agent/target/support-agent.jar --agent.variant=broken \
  --spring.ai.openai.base-url=http://localhost:9999/v1 --spring.ai.openai.api-key=rehearsal   # terminal 2
still-lies rehearse-gate              # terminal 3: exit 1; restart terminal 2 without --agent.variant=broken: exit 0
```

## Temporal

```bash
temporal server start-dev                                       # terminal 1
still-lies temporal worker                                      # terminal 2
still-lies temporal start --review-reminder PT2M --verify-after PT1M
still-lies temporal status
still-lies temporal review                                      # after saving data/reviewed.csv
still-lies temporal gate --passed true --deployed-at '2026-11-14 10:00:00+01'
```

The workflow runs the same steps as the commands above: export, cases, a durable wait for the reviewer,
dataset, an optional pull request (`OPEN_PR=true`), a wait for the CI gate, a 24-hour timer, and the
before/after comparison. When billing hasn't recovered, it continues as new, up to three attempts.

## Layout

| Path | What |
|---|---|
| `agent/` | the Spring Boot + Spring AI support agent and its policies |
| `agent/.../GenAiMessagesObservationFilter.java` | puts the prompt and answer on the chat span, for the Dash0 evals |
| `dash0/evals/` | the three Dash0 evals as code |
| `sql/` | every data step, in DuckDB SQL |
| `evals/` | the harness-evals config and the golden dataset |
| `.evals/baselines/` | the stored baseline the gate compares against |
| `cli/` | the `still-lies` command line: steps, DuckDB over JDBC, Temporal, the rehearsal model |
| `.github/workflows/evals.yml` | the CI gate |
| `samples/` | exports for running without Dash0 |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Chat spans in Dash0 without `gen_ai.input.messages` | Make sure `GenAiMessagesObservationFilter` is registered as a bean, and that you started with `--spring.profiles.active=dash0` |
| Only 1 in 10 traces in Dash0 | `management.tracing.sampling.probability` must be 1.0 (it is in `application.yaml`) |
| Spans arrive, but no verdicts | Evals created after the traffic (no backfill), or a filter doesn't match `service.name = support-agent` |
| `run this from the repository root` | `cd` to the folder with `sql/` and `evals/` |
| harness-evals exit 2 | Config error: no `OPENAI_API_KEY`, or no baseline for `eval-baseline` (check `.evals/baselines/`) |
| `could not start uvx` | Install uv, or `pipx install 'harness-evals[llm,harness]==0.29.1'` (an installed harness-evals is used first) |
| `temporal start`: connection refused | Start `temporal server start-dev` first |
