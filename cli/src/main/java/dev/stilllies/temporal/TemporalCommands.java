package dev.stilllies.temporal;

import dev.stilllies.cli.Args;
import dev.stilllies.cli.Env;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * The Temporal subcommands of the CLI, run from the repository root:
 *
 * <pre>
 *   temporal worker                                         run the worker (keep it running)
 *   temporal start  [--from now-24h] [--review-reminder PT48H] [--verify-after PT24H]
 *   temporal review [--id ID] [--csv data/reviewed.csv]     signal that the review is done
 *   temporal gate   --deployed-at '2026-11-14 10:00:00+01' [--passed true] [--id ID]
 *   temporal status [--id ID]                               print where the incident is
 * </pre>
 *
 * Everything connects to the local Temporal dev server (temporal server start-dev) on
 * localhost:7233. The default workflow ID is today's: eval-incident-billing-YYYY-MM-DD.
 */
public final class TemporalCommands {

  static final String TASK_QUEUE = "still-lies";

  private TemporalCommands() {}

  /** Returns true when the command keeps running (the worker). */
  public static boolean run(Args args) {
    String sub = args.positional(0, "help");
    if (sub.equals("help")) {
      usage();
      return false;
    }
    WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
    WorkflowClient client = WorkflowClient.newInstance(service);
    String id = args.option("id", defaultId());
    switch (sub) {
      case "worker" -> {
        runWorker(client);
        return true;
      }
      case "start" -> start(client, args);
      case "review" -> stub(client, id).reviewSubmitted(args.option("csv", "data/reviewed.csv"));
      case "gate" -> stub(client, id).gateFinished(
          Boolean.parseBoolean(args.option("passed", "true")), args.required("deployed-at"));
      case "status" -> System.out.println(stub(client, id).status());
      default -> usage();
    }
    return false;
  }

  private static void runWorker(WorkflowClient client) {
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(EvalIncidentWorkflowImpl.class);
    worker.registerActivitiesImplementations(new EvalActivitiesImpl());
    factory.start();
    System.out.println("worker running on task queue '" + TASK_QUEUE + "' for " + Path.of("").toAbsolutePath());
  }

  private static void start(WorkflowClient client, Args args) {
    String id = defaultId();
    Incident incident = new Incident(
        id,
        args.option("from", Env.get("FROM", "now-24h")),
        args.option("review-reminder", "PT48H"),
        args.option("verify-after", "PT24H"),
        1);
    EvalIncidentWorkflow workflow = client.newWorkflowStub(
        EvalIncidentWorkflow.class,
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId(id).build());
    try {
      WorkflowClient.start(workflow::run, incident);
      System.out.println("started " + id);
    } catch (WorkflowExecutionAlreadyStarted e) {
      // The same alert firing twice on one day is the same incident.
      System.out.println(id + " is already running");
    }
  }

  private static EvalIncidentWorkflow stub(WorkflowClient client, String workflowId) {
    return client.newWorkflowStub(EvalIncidentWorkflow.class, workflowId);
  }

  /** One incident per topic per day, so repeated alerts don't start duplicates. */
  private static String defaultId() {
    return "eval-incident-billing-" + LocalDate.now();
  }

  private static void usage() {
    System.out.println("""
        temporal worker
        temporal start  [--from now-24h] [--review-reminder PT48H] [--verify-after PT24H]
        temporal review [--id ID] [--csv data/reviewed.csv]
        temporal gate   --deployed-at '2026-11-14 10:00:00+01' [--passed true|false] [--id ID]
        temporal status [--id ID]""");
  }
}
