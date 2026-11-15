package dev.stilllies.cli;

import dev.stilllies.temporal.TemporalCommands;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every step of the demo, in the order you use them. Run from the repository root:
 *
 * <pre>
 *   java -jar cli/target/still-lies.jar help
 * </pre>
 */
public final class Main {

  private static final String HELP = """
      usage: java -jar cli/target/still-lies.jar <command> [options]   (run from the repository root)

      setup
        check                         check every tool and setting the demo needs
      phase 2: the agent and traffic
        traffic [--rounds 3]          send traffic/questions.txt to the agent (AGENT_URL)
      phases 3 and 4: export, DuckDB, dataset
        export [--from now-24h]       eval verdicts and agent spans from Dash0 into data/
        samples                       use the bundled exports in samples/ instead (offline)
        report                        DuckDB: groundedness fail rate by topic
        cases                         DuckDB: pick the cases and write data/to_review.csv
        dataset                       DuckDB: scrub PII and write evals/billing-regressions.jsonl
        verify --deployed-at '2026-11-14 10:00:00+01'
                                      DuckDB: billing fail rate before and after the deploy
      phases 5 and 6: offline evals and the gate (harness-evals, run with uvx)
        eval                          score the running agent against the golden dataset
        eval-baseline                 score it and save the scores as the baseline, if they pass
        gate                          what CI runs: fail on a regression or a score below 0.8
        flaky [--runs 5]              run the suite several times and list flaky cases
      phase 8: Temporal
        temporal worker|start|review|gate|status   (temporal help for the options)
      rehearsal without OpenAI
        rehearsal-model [--port 9999] a stand-in OpenAI model
        rehearse-gate                 the gate against the stand-in model
      clean                           remove data/ and results/
      """;

  private Main() {}

  public static void main(String[] argv) throws Exception {
    Args args = new Args(argv);
    String command = args.positional(0, "help");
    if (!command.equals("help") && !Files.isDirectory(Path.of("sql"))) {
      System.err.println("run this from the repository root (the folder with sql/ and evals/)");
      System.exit(2);
    }
    int exit = 0;
    try {
      switch (command) {
        case "check" -> exit = Steps.check();
        case "traffic" -> Steps.traffic(args.intOption("rounds", 3), Env.get("AGENT_URL", "http://localhost:8080/answer"));
        case "export" -> Steps.export(args.option("from", Env.get("FROM", "now-24h")));
        case "export-verdicts" -> Steps.exportVerdicts(args.option("from", Env.get("FROM", "now-24h")));
        case "export-spans" -> Steps.exportSpans(args.option("from", Env.get("FROM", "now-24h")));
        case "samples" -> Steps.samples();
        case "report" -> Steps.report();
        case "cases" -> Steps.cases();
        case "dataset" -> Steps.dataset();
        case "verify" -> Steps.verify(args.required("deployed-at"));
        case "eval" -> exit = Steps.eval();
        case "eval-baseline" -> exit = Steps.evalBaseline();
        case "gate" -> exit = Steps.gate();
        case "flaky" -> exit = Steps.flaky(args.intOption("runs", 5), args.option("config", Steps.EVAL_CONFIG));
        case "temporal" -> {
          if (TemporalCommands.run(args.shift(1))) {
            return; // the worker keeps running
          }
        }
        case "rehearsal-model" -> {
          RehearsalModel.serve(args.intOption("port", 9999));
          return; // keeps serving until Ctrl+C
        }
        case "rehearse-gate" -> exit = Steps.rehearseGate();
        case "clean" -> Steps.clean();
        default -> System.out.print(HELP);
      }
    } catch (RuntimeException e) {
      System.err.println("error: " + e.getMessage());
      exit = exit == 0 ? 1 : exit;
      if (exit == 1 && e instanceof IllegalArgumentException) {
        exit = 2;
      }
    }
    System.exit(exit);
  }
}
