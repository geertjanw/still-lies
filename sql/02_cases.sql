-- Step 2: join the exported spans with their verdicts and pick the test cases.
-- Input:  data/spans.csv (still-lies export-spans, or still-lies samples) and the per_span view from step 1.
-- Output: table cases, one row per distinct billing question.

CREATE OR REPLACE TABLE spans AS
SELECT *
FROM read_csv('data/spans.csv', header = true, all_varchar = true);

-- The message attributes follow the OTel GenAI conventions:
-- a JSON array of {"role": ..., "parts": [...]} objects.
CREATE OR REPLACE TABLE cases AS
WITH parsed AS (
    SELECT
        s."otel.span.id" AS span_id,
        from_json(s."gen_ai.input.messages", '[{"role": "VARCHAR", "parts": ["JSON"]}]') AS input_msgs,
        s."gen_ai.output.messages" AS output_messages
    FROM spans s
),
extracted AS (
    SELECT
        span_id,
        -- the customer's question: the last message with role "user"
        list_filter(input_msgs, lambda m: m.role = 'user')[-1].parts[1] ->> '$.content' AS question,
        -- the retrieved policy text: the last message with role "tool"
        list_filter(input_msgs, lambda m: m.role = 'tool')[-1].parts[1] ->> '$.response' AS policy_text,
        json_extract_string(output_messages, '$[0].parts[0].content') AS agent_answer
    FROM parsed
)
SELECT
    e.span_id AS id,
    e.question,
    e.agent_answer,
    e.policy_text,
    p.groundedness,
    p.why,
    p.judged_at
FROM extracted e
JOIN per_span p USING (span_id)
WHERE p.topic = 'billing'
  -- every failure, plus a stable ~10 percent of passes (same rows on every rerun)
  AND (p.groundedness = 'fail' OR hash(p.span_id) % 10 = 0)
-- one case per distinct question; prefer a failing example
QUALIFY row_number() OVER (
    PARTITION BY lower(trim(e.question))
    ORDER BY (p.groundedness = 'fail') DESC, p.judged_at DESC
) = 1;

SELECT groundedness, count(*) AS cases FROM cases GROUP BY groundedness ORDER BY groundedness;
