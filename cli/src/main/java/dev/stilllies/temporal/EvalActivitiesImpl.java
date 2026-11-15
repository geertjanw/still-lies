package dev.stilllies.temporal;

import dev.stilllies.cli.Duck;
import dev.stilllies.cli.Env;
import dev.stilllies.cli.Proc;
import dev.stilllies.cli.Steps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The activities call the same {@link Steps} as the command line, so the workflow runs exactly
 * the same dash0 CLI commands and DuckDB SQL files you run by hand. The worker runs in the
 * repository root, and every path is relative to it.
 */
public class EvalActivitiesImpl implements EvalActivities {

  private static final Logger log = LoggerFactory.getLogger(EvalActivitiesImpl.class);

  /** Billing counts as recovered when at most this share of judged answers fails after the deploy. */
  private static final double RECOVERED_MAX_FAIL_PCT = 5.0;

  @Override
  public void exportFromDash0(Incident incident) {
    Steps.export(incident.from());
  }

  @Override
  public int buildCases(Incident incident) {
    return (int) Steps.cases();
  }

  @Override
  public void notifyReviewer(Incident incident, int cases) {
    log.info(
        "REVIEW NEEDED for {}: fill in the expected column of data/to_review.csv ({} cases), save it as "
            + "data/reviewed.csv, then run: java -jar cli/target/still-lies.jar temporal review",
        incident.id(), cases);
  }

  @Override
  public void remindReviewer(Incident incident) {
    log.info("REMINDER for {}: data/to_review.csv is still waiting for expected answers", incident.id());
  }

  @Override
  public int writeDataset(Incident incident, String reviewedCsvPath) {
    Path source = Path.of(reviewedCsvPath).toAbsolutePath().normalize();
    Path target = Path.of("data/reviewed.csv").toAbsolutePath().normalize();
    try {
      if (!source.equals(target)) {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("could not copy " + source + " to " + target, e);
    }
    return (int) Steps.dataset();
  }

  @Override
  public String openPullRequest(Incident incident) {
    if (!"true".equalsIgnoreCase(Env.get("OPEN_PR"))) {
      log.info("OPEN_PR is not true: commit evals/billing-regressions.jsonl yourself");
      return "skipped";
    }
    // The branch is named after the incident, so a retried activity reuses it instead of opening a second PR.
    String branch = "eval-incident/" + incident.id();
    if (Proc.exitCode(List.of("git", "switch", branch), Map.of(), null) != 0) {
      Proc.run(List.of("git", "switch", "-c", branch));
    }
    Proc.run(List.of("git", "add", "evals/billing-regressions.jsonl"));
    Proc.exitCode(List.of("git", "commit", "-m", "Add billing regression cases from " + incident.id()), Map.of(), null);
    Proc.run(List.of("git", "push", "-u", "origin", branch));
    String existing = Proc.output(List.of("gh", "pr", "view", branch, "--json", "url", "--jq", ".url"));
    if (!existing.isBlank()) {
      return existing.trim();
    }
    return Proc.output(List.of(
            "gh", "pr", "create", "--head", branch,
            "--title", "Eval regressions from " + incident.id(),
            "--body", "New golden cases from production failures found by Dash0 Evals. "
                + "CI runs harness-evals against them."))
        .trim();
  }

  @Override
  public Comparison compareBeforeAfter(Incident incident, String deployedAt) {
    Steps.exportVerdicts(incident.from());
    Steps.report();
    String sql =
        """
        SELECT
          count(*) FILTER (WHERE judged_at <  ?::TIMESTAMPTZ),
          coalesce(100 * avg((groundedness = 'fail')::INT) FILTER (WHERE judged_at <  ?::TIMESTAMPTZ), 0),
          count(*) FILTER (WHERE judged_at >= ?::TIMESTAMPTZ),
          coalesce(100 * avg((groundedness = 'fail')::INT) FILTER (WHERE judged_at >= ?::TIMESTAMPTZ), 0)
        FROM per_span
        WHERE topic = 'billing' AND groundedness IS NOT NULL
        """;
    try (Duck duck = Duck.file();
        PreparedStatement ps = duck.connection().prepareStatement(sql)) {
      for (int i = 1; i <= 4; i++) {
        ps.setString(i, deployedAt);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        long before = rs.getLong(1);
        double failBefore = rs.getDouble(2);
        long after = rs.getLong(3);
        double failAfter = rs.getDouble(4);
        boolean recovered = after > 0 && failAfter <= RECOVERED_MAX_FAIL_PCT;
        return new Comparison(before, failBefore, after, failAfter, recovered);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not compare before and after " + deployedAt, e);
    }
  }
}
