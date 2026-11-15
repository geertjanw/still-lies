package dev.stilllies.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface EvalIncidentWorkflow {

  /** Runs one incident from Dash0 export to verified fix. */
  @WorkflowMethod
  IncidentResult run(Incident incident);

  /** Sent by the reviewer once the expected answers are filled in. */
  @SignalMethod
  void reviewSubmitted(String reviewedCsvPath);

  /** Sent when the eval gate in CI finished: passed or not, and when the fix was deployed. */
  @SignalMethod
  void gateFinished(boolean passed, String deployedAt);

  /** Where the incident is right now, for `temporal workflow query` or the Temporal UI. */
  @QueryMethod
  String status();
}
