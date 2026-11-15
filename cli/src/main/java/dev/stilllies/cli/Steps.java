package dev.stilllies.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Every step of the demo. The Temporal activities call the same methods. */
public final class Steps {

  public static final String EVAL_CONFIG = "evals/billing.eval.yaml";
  public static final String HARNESS_EVALS = "harness-evals[llm,harness]==0.29.1";

  private Steps() {}

  // ------------------------------------------------------------------ setup

  public static int check() {
    List<String> missing = new ArrayList<>();
    if (!Files.isDirectory(Path.of("sql"))) {
      missing.add("run this from the repository root (no sql/ folder here)");
    }
    if (!Proc.onPath("dash0")) {
      missing.add("dash0 CLI on your PATH");
    }
    if (!Proc.onPath("harness-evals") && !Proc.onPath("uvx")) {
      missing.add("uvx (from uv) or harness-evals on your PATH");
    }
    if (!Env.hasDotenv()) {
      missing.add(".env (copy .env.example to .env and fill it in)");
    }
    for (String v : List.of("OPENAI_API_KEY", "DASH0_OTLP_URL", "DASH0_API_URL", "DASH0_AUTH_TOKEN", "DASH0_DATASET")) {
      if (Env.get(v) == null || Env.get(v).isBlank()) {
        missing.add(v + " in .env");
      }
    }
    if (!Files.exists(Path.of("agent/target/support-agent.jar"))) {
      missing.add("agent/target/support-agent.jar (run: mvn -q package -DskipTests)");
    }
    if (missing.isEmpty()) {
      System.out.println("all good: java " + Runtime.version().feature()
          + ", dash0 " + Proc.output(List.of("dash0", "--version")).lines().findFirst().orElse("?"));
      return 0;
    }
    missing.forEach(m -> System.out.println("missing: " + m));
    return 1;
  }

  // ------------------------------------------------------------------ phase 1 and 2: traffic and Dash0 evals

  /** Sends every question in traffic/questions.txt to the agent, {@code rounds} times. */
  public static void traffic(int rounds, String agentUrl) {
    ObjectMapper json = new ObjectMapper();
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    List<String> questions;
    try {
      questions = Files.readAllLines(Path.of("traffic/questions.txt")).stream().filter(q -> !q.isBlank()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (int round = 1; round <= rounds; round++) {
      for (String q : questions) {
        System.out.println(q);
        try {
          String body = json.writeValueAsString(Map.of("input", q));
          HttpRequest request = HttpRequest.newBuilder(URI.create(agentUrl))
              .timeout(Duration.ofSeconds(60))
              .header("content-type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
          String answer = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
          System.out.println(answer.length() > 160 ? answer.substring(0, 160) : answer);
        } catch (IOException e) {
          throw new IllegalStateException("could not reach the agent at " + agentUrl + ": is it running?", e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        System.out.println();
      }
    }
  }

  // ------------------------------------------------------------------ phases 3 and 4: export, DuckDB, dataset

  public static void export(String from) {
    exportVerdicts(from);
    exportSpans(from);
  }

  /** The eval verdicts: one gen_ai.evaluation.result log record per eval per judged span. */
  public static void exportVerdicts(String from) {
    mkdirs("data");
    Path out = Path.of("data/verdicts.csv");
    Proc.toFile(List.of("dash0", "logs", "query", "--from", from, "--limit", "100000", "--precision", "disabled",
        "--filter", "dash0.gen_ai.evaluation.source is judge",
        "--column", "otel.log.time", "--column", "otel.trace.id", "--column", "otel.span.id",
        "--column", "dash0.gen_ai.evaluation.display_name",
        "--column", "gen_ai.evaluation.score.label",
        "--column", "gen_ai.evaluation.explanation",
        "-o", "csv"), out);
    try (Duck duck = Duck.memory()) {
      System.out.println(out + ": " + duck.queryLong("SELECT count(*) FROM read_csv('" + out + "', header = true)") + " verdicts");
    }
  }

  /** The agent's chat spans, with the prompt and the answer. */
  public static void exportSpans(String from) {
    mkdirs("data");
    Path out = Path.of("data/spans.csv");
    Proc.toFile(List.of("dash0", "spans", "query", "--from", from, "--limit", "100000", "--precision", "disabled",
        "--filter", "service.name is support-agent",
        "--filter", "gen_ai.operation.name is chat",
        "--column", "otel.span.start_time", "--column", "otel.trace.id", "--column", "otel.span.id",
        "--column", "gen_ai.input.messages", "--column", "gen_ai.output.messages",
        "-o", "csv"), out);
    try (Duck duck = Duck.memory()) {
      System.out.println(out + ": " + duck.queryLong("SELECT count(*) FROM read_csv('" + out + "', header = true)") + " spans");
    }
  }

  /** The bundled exports instead of Dash0, for the offline demo. */
  public static void samples() {
    mkdirs("data");
    for (String f : List.of("spans.csv", "verdicts.csv", "reviewed.csv")) {
      copy(Path.of("samples", f), Path.of("data", f));
    }
    System.out.println("copied samples into data/");
  }

  /** sql/01: groundedness fail rate by topic. */
  public static void report() {
    try (Duck duck = Duck.file()) {
      duck.runFile("sql/01_per_span.sql", true);
    }
  }

  /** sql/01 to 03: pick the test cases and write data/to_review.csv. Returns the number of cases. */
  public static long cases() {
    try (Duck duck = Duck.file()) {
      duck.runFile("sql/01_per_span.sql", true);
      duck.runFile("sql/02_cases.sql", true);
      duck.runFile("sql/03_review.sql", true);
      System.out.println("Next: fill in the expected column of data/to_review.csv and save it as data/reviewed.csv");
      return duck.queryLong("SELECT count(*) FROM cases");
    }
  }

  /** sql/04: scrub PII and write evals/billing-regressions.jsonl. Returns the number of goldens. */
  public static long dataset() {
    if (!Files.exists(Path.of("data/reviewed.csv"))) {
      throw new IllegalStateException("missing data/reviewed.csv (see the cases step)");
    }
    try (Duck duck = Duck.file()) {
      duck.runFile("sql/01_per_span.sql", false);
      duck.runFile("sql/02_cases.sql", false);
      duck.runFile("sql/04_dataset.sql", true);
      return duck.queryLong("SELECT count(*) FROM read_json('evals/billing-regressions.jsonl', format = 'newline_delimited')");
    }
  }

  /** sql/05: billing fail rate before and after the deploy. */
  public static void verify(String deployedAt) {
    try (Duck duck = Duck.file()) {
      duck.runFile("sql/01_per_span.sql", false);
      duck.run("SET VARIABLE deployed_at = TIMESTAMPTZ '" + deployedAt.replace("'", "''") + "'", false);
      duck.runFile("sql/05_before_after.sql", true);
    }
  }

  // ------------------------------------------------------------------ phases 5 and 6: offline evals and the gate

  /** Scores the running agent against the golden dataset. */
  public static int eval() {
    return harness(List.of("run", EVAL_CONFIG), Map.of(), null);
  }

  /** Scores the running agent and saves the scores as the new baseline, only if they pass. */
  public static int evalBaseline() {
    return harness(List.of("run", EVAL_CONFIG, "--fail-under", "0.8", "--update-baseline"), Map.of(), null);
  }

  /** What CI runs: fails on a regression against the baseline or a score below 0.8. */
  public static int gate() {
    return harness(List.of("run", EVAL_CONFIG, "--baseline", "--fail-under", "0.8"), Map.of(), null);
  }

  /** Runs the suite {@code runs} times, then lists cases whose outcome changed (sql/06). */
  public static int flaky(int runs, String config) {
    deleteRecursively(Path.of("results"));
    mkdirs("results");
    for (int i = 1; i <= runs; i++) {
      Path log = Path.of("results/log-" + i + ".txt");
      int exit = harness(List.of("run", config, "--no-progress"), Map.of(), log);
      System.out.println("run " + i + ": exit " + exit + " (log: " + log + ")");
      sleep(1000);
    }
    if (listGlob(Path.of("results"), "run-*.jsonl").isEmpty()) {
      System.out.println("no results written; see the last lines of results/log-1.txt:");
      tail(Path.of("results/log-1.txt"), 5);
      return 1;
    }
    try (Duck duck = Duck.memory()) {
      duck.runFile("sql/06_flaky.sql", true);
    }
    return 0;
  }

  /**
   * harness-evals is a Python tool. The repo has no Python code: it runs harness-evals with uvx,
   * which downloads the pinned version into a cache on first use. An installed harness-evals wins.
   */
  public static int harness(List<String> args, Map<String, String> extraEnv, Path logFile) {
    List<String> command = new ArrayList<>();
    if (Proc.onPath("harness-evals")) {
      command.add("harness-evals");
    } else {
      command.addAll(List.of("uvx", "--from", HARNESS_EVALS, "harness-evals"));
    }
    command.addAll(args);
    Map<String, String> env = new java.util.HashMap<>(extraEnv);
    env.putIfAbsent("AGENT_URL", Env.get("AGENT_URL", "http://localhost:8080/answer"));
    return Proc.exitCode(command, env, logFile);
  }

  // ------------------------------------------------------------------ rehearsal and housekeeping

  /** The gate against the rehearsal model, without the two metrics it can't serve. */
  public static int rehearseGate() {
    Path rehearsal = Path.of("evals/rehearsal.eval.yaml");
    try {
      List<String> lines = Files.readAllLines(Path.of(EVAL_CONFIG));
      List<String> kept = new ArrayList<>();
      for (int i = 0; i < lines.size(); i++) {
        String l = lines.get(i).strip();
        if (l.equals("- kind: faithfulness") || l.equals("- kind: answer_correctness")) {
          i++; // and its threshold line
          continue;
        }
        kept.add(lines.get(i));
      }
      Files.write(rehearsal, kept);
      return harness(List.of("run", rehearsal.toString(), "--baseline", "--fail-under", "0.8"),
          Map.of("OPENAI_BASE_URL", "http://localhost:9999/v1", "OPENAI_API_KEY", "rehearsal"), null);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      try {
        Files.deleteIfExists(rehearsal);
      } catch (IOException e) {
        // ignore
      }
    }
  }

  /** Removes local data and results; keeps the committed dataset and baseline. */
  public static void clean() {
    deleteRecursively(Path.of("data"));
    deleteRecursively(Path.of("results"));
    System.out.println("removed data/ and results/");
  }

  // ------------------------------------------------------------------ helpers

  static void mkdirs(String dir) {
    try {
      Files.createDirectories(Path.of(dir));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void copy(Path from, Path to) {
    try {
      Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("could not copy " + from + " to " + to, e);
    }
  }

  static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not delete " + dir, e);
    }
  }

  static List<Path> listGlob(Path dir, String glob) {
    List<Path> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
      ds.forEach(out::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  static void tail(Path file, int n) {
    try {
      List<String> lines = Files.readAllLines(file);
      lines.subList(Math.max(0, lines.size() - n), lines.size()).forEach(System.out::println);
    } catch (IOException e) {
      System.out.println("(could not read " + file + ")");
    }
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
