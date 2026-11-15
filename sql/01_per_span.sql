-- Step 1: load the eval verdicts exported from Dash0 and put each span's verdicts side by side.
-- Input:  data/verdicts.csv  (still-lies export-verdicts, or still-lies samples)
-- Output: table verdicts, view per_span, and a fail-rate-by-topic report.

CREATE OR REPLACE TABLE verdicts AS
SELECT *
FROM read_csv('data/verdicts.csv', header = true, all_varchar = true);

-- Each eval writes its own log record for the same span.
-- One row per judged span, with every eval's verdict as a column.
CREATE OR REPLACE VIEW per_span AS
SELECT
    "otel.span.id"                                         AS span_id,
    any_value("otel.trace.id")                             AS trace_id,
    min("otel.log.time")::TIMESTAMPTZ                      AS judged_at,
    max("gen_ai.evaluation.score.label")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Groundedness')          AS groundedness,
    max("gen_ai.evaluation.explanation")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Groundedness')          AS why,
    max("gen_ai.evaluation.score.label")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Answered the question') AS answered,
    max("gen_ai.evaluation.score.label")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Topic')                 AS topic
FROM verdicts
GROUP BY "otel.span.id";

-- The breakdown the Dash0 UI can't show in one view: groundedness fail rate per topic.
SELECT
    topic,
    count(*)                                                       AS judged,
    round(100 * avg((groundedness = 'fail')::INT), 1)              AS fail_pct,
    count(*) FILTER (groundedness = 'fail' AND why ILIKE '%refund%') AS refund_mentions
FROM per_span
WHERE groundedness IS NOT NULL
GROUP BY topic
ORDER BY fail_pct DESC;
