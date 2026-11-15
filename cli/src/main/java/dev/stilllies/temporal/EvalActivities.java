package dev.stilllies.temporal;

import io.temporal.activity.ActivityInterface;

/** Everything that touches the outside world: the dash0 CLI, DuckDB, git and gh. */
@ActivityInterface
public interface EvalActivities {

  /** The export step: eval verdicts and agent spans from Dash0 into data/. */
  void exportFromDash0(Incident incident);

  /** The cases step: DuckDB picks the cases and writes data/to_review.csv. Returns the number of cases. */
  int buildCases(Incident incident);

  /** Tells the reviewer what to do. In this sample it logs; in real life it posts to Slack. */
  void notifyReviewer(Incident incident, int cases);

  /** Nudges the reviewer again. */
  void remindReviewer(Incident incident);

  /** Copies the reviewed CSV into place and runs the dataset step. Returns the number of goldens. */
  int writeDataset(Incident incident, String reviewedCsvPath);

  /** Opens a pull request with the new dataset (only when OPEN_PR=true). Returns its URL or "skipped". */
  String openPullRequest(Incident incident);

  /** Re-exports the verdicts and compares billing groundedness before and after the deploy. */
  Comparison compareBeforeAfter(Incident incident, String deployedAt);
}
