-- Step 3: hand the cases to a person who writes the correct answer for each.
-- Input:  table cases from step 2.
-- Output: data/to_review.csv. Fill in the "expected" column, then save it as data/reviewed.csv.

COPY (
    SELECT
        id,
        question,
        agent_answer,
        groundedness,
        why AS judge_explanation,
        '' AS expected
    FROM cases
    ORDER BY groundedness, question
) TO 'data/to_review.csv' (HEADER);

SELECT count(*) AS rows_to_review FROM cases;
