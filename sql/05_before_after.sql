-- Step 7: compare billing groundedness before and after the fix was deployed.
-- Input:  the per_span view from step 1 (re-export the verdicts first),
--         and the deploy time in the deployed_at variable:
--         still-lies verify --deployed-at '2026-11-14 10:00:00+00'  (it runs SET VARIABLE first)

SELECT
    CASE WHEN judged_at < getvariable('deployed_at') THEN 'before' ELSE 'after' END AS period,
    count(*)                                                AS judged,
    round(100 * avg((groundedness = 'fail')::INT), 1)       AS fail_pct
FROM per_span
WHERE topic = 'billing'
  AND groundedness IS NOT NULL
GROUP BY period
ORDER BY period DESC;

-- Any billing failures left after the deploy? Read the judge's reasons to see whether it's the old pattern.
SELECT judged_at, why
FROM per_span
WHERE topic = 'billing'
  AND groundedness = 'fail'
  AND judged_at >= getvariable('deployed_at')
ORDER BY judged_at
LIMIT 10;
