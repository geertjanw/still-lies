package dev.stilllies.temporal;

/** Billing groundedness before and after the deploy, read from the per_span view in DuckDB. */
public record Comparison(
    long judgedBefore, double failPctBefore, long judgedAfter, double failPctAfter, boolean recovered) {

  @Override
  public String toString() {
    return String.format(
        "before: %d judged, %.1f%% fail; after: %d judged, %.1f%% fail; recovered: %s",
        judgedBefore, failPctBefore, judgedAfter, failPctAfter, recovered);
  }
}
