-- Step 4: scrub PII and write the golden dataset for harness-evals.
-- Input:  table cases from step 2, and data/reviewed.csv (to_review.csv with "expected" filled in).
-- Output: evals/billing-regressions.jsonl, one golden per line. Commit it.

-- A first pass at PII: email addresses and order or invoice numbers.
-- Add patterns for your own formats, and still skim the file before you commit it.
CREATE OR REPLACE MACRO scrub(t) AS
    regexp_replace(
        regexp_replace(t, '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', '<email>', 'g'),
        '\b(ORD|INV)-?[0-9]{4,}\b', '<order>', 'g');

COPY (
    SELECT
        c.id,
        scrub(r.question)          AS input,
        r.expected,
        [scrub(c.policy_text)]     AS context,
        ['lookup_policy']          AS expected_tools,
        {'topic': 'billing', 'source': 'dash0'} AS tags
    FROM cases c
    JOIN read_csv('data/reviewed.csv', header = true, all_varchar = true) r USING (id)
    WHERE coalesce(trim(r.expected), '') <> ''
    ORDER BY c.id
) TO 'evals/billing-regressions.jsonl' (FORMAT json);

SELECT count(*) AS goldens
FROM read_json('evals/billing-regressions.jsonl', format = 'newline_delimited');
