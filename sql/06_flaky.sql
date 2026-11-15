-- Find flaky test cases: rows that passed in some runs and failed in others.
-- Input:  results/run-*.jsonl, one file per harness-evals run (still-lies flaky runs the suite 5 times).
-- Output: one row per case and metric whose outcome changed between runs.

WITH records AS (
    SELECT *
    FROM read_json('results/run-*.jsonl',
                   format = 'newline_delimited',
                   union_by_name = true,
                   filename = true)
    WHERE scores IS NOT NULL          -- skip the summary record at the end of each file
),
scores AS (
    SELECT
        id,
        input,
        filename,
        unnest(scores) AS s
    FROM records
)
SELECT
    id,
    left(input, 60)                   AS question,
    s.name                            AS metric,
    count(DISTINCT filename)          AS runs,
    sum(s.passed::INT)                AS passes,
    round(min(s.value), 2)            AS min_score,
    round(max(s.value), 2)            AS max_score
FROM scores
GROUP BY ALL
HAVING sum(s.passed::INT) NOT IN (0, count(*))
ORDER BY max_score - min_score DESC, id;
