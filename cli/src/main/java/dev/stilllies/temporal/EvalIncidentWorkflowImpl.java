package dev.stilllies.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * The incident loop from the talk, as one durable workflow.
 *
 * <p>All I/O happens in activities, so this code only waits and decides. Every wait (for the
 * reviewer, for CI, for a day after the deploy) survives worker restarts.
 */
public class EvalIncidentWorkflowImpl implements EvalIncidentWorkflow {

  private static final int MAX_ATTEMPTS = 3;
  private static final Logger log = Workflow.getLogger(EvalIncidentWorkflowImpl.class);

  private final EvalActivities act =
      Workflow.newActivityStub(
          EvalActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(5))
                      .setMaximumAttempts(3)
                      .build())
              .build());

  private String reviewedCsv;
  private Boolean gatePassed;
  private String deployedAt;
  private String status = "starting";

  @Override
  public IncidentResult run(Incident incident) {
    status = "exporting from Dash0";
    act.exportFromDash0(incident);

    status = "building cases";
    int cases = act.buildCases(incident);
    if (cases == 0) {
      status = "closed: no failing billing answers to turn into tests";
      return new IncidentResult(incident.id(), status, null, null);
    }

    // Wait for the human. Nudge them every reviewReminder until they answer.
    act.notifyReviewer(incident, cases);
    status = "waiting for review of " + cases + " cases";
    Duration reminder = Duration.parse(incident.reviewReminder());
    while (!Workflow.await(reminder, () -> reviewedCsv != null)) {
      act.remindReviewer(incident);
    }

    status = "writing the dataset";
    int goldens = act.writeDataset(incident, reviewedCsv);
    log.info("dataset written with {} goldens", goldens);

    status = "opening a pull request";
    String pullRequest = act.openPullRequest(incident);

    // A red gate just means more work on the fix. Wait for the next CI run's signal.
    status = "waiting for the eval gate in CI";
    while (true) {
      Workflow.await(() -> gatePassed != null);
      if (gatePassed) {
        break;
      }
      log.info("gate failed; waiting for the next run");
      gatePassed = null;
    }

    // Durable timer: the worker can restart, the deploy can roll, the wait survives.
    status = "deployed at " + deployedAt + "; waiting " + incident.verifyAfter() + " before verifying";
    Workflow.sleep(Duration.parse(incident.verifyAfter()));

    status = "comparing before and after";
    Comparison comparison = act.compareBeforeAfter(incident, deployedAt);
    log.info("comparison: {}", comparison);

    if (!comparison.recovered() && incident.attempt() < MAX_ATTEMPTS) {
      // Not fixed yet: start over with fresh data, as a new run of the same workflow ID.
      Workflow.continueAsNew(incident.reopened());
    }

    status = comparison.recovered() ? "closed: recovered" : "closed: not recovered after " + MAX_ATTEMPTS + " attempts";
    return new IncidentResult(incident.id(), status, pullRequest, comparison);
  }

  @Override
  public void reviewSubmitted(String reviewedCsvPath) {
    this.reviewedCsv = reviewedCsvPath;
  }

  @Override
  public void gateFinished(boolean passed, String deployedAt) {
    this.gatePassed = passed;
    this.deployedAt = deployedAt;
  }

  @Override
  public String status() {
    return status;
  }
}
