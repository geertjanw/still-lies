# From Production Lie to CI Gate: Building the Evals Loop Step by Step

Nov 12, 2026 · @Geertjan

## What you'll build

This guide builds the sample for the talk **When Your LLM Passes Every Check And Still Lies**, file by file, from an empty folder.

A Spring Boot + Spring AI agent produces the traces, and one jar, `evals-gate.jar`, runs every other step. harness-evals is the only Python, and the jar runs it with `uvx`.

### The finished layout

```
evals-gate/
├── pom.xml                         aggregator: builds agent and cli
├── .env.example                    copy to .env (never committed)
├── .gitignore
├── README.md
├── agent/                          Spring Boot 4.1 + Spring AI 2.0
│   ├── pom.xml
│   └── src/main/
│       ├── java/dev/stilllies/agent/
│       │   ├── SupportAgentApplication.java
│       │   ├── AnswerController.java
│       │   ├── PolicyTools.java
│       │   └── GenAiMessagesObservationFilter.java
│       └── resources/
│           ├── application.yaml
│           ├── application-dash0.yaml
│           └── policies/ refunds.md shipping.md account.md
├── cli/                            the evals-gate command line
│   ├── pom.xml
│   └── src/main/java/dev/stilllies/
│       ├── cli/      Main Args Env Proc Duck Steps RehearsalModel
│       └── temporal/ Incident Comparison IncidentResult EvalIncidentWorkflow(Impl)
│                     EvalActivities(Impl) TemporalCommands
├── traffic/questions.txt
├── dash0/evals/                    the three Dash0 evals as code
├── sql/01..06_*.sql                every data step, in DuckDB SQL
├── evals/billing.eval.yaml         the harness-evals config
├── evals/billing-regressions.jsonl the golden dataset (generated, committed)
├── .evals/baselines/               the stored baseline
├── samples/                        exports for running without Dash0
└── .github/workflows/evals.yml     the CI gate
```

### How the steps map to the talk

| Guide step | Talk step | What you build |
| --- | --- | --- |
| 1–3 | setup | Tools, skeleton, the CLI jar |
| 4–5 | 1 Instrument | The agent, with prompt and answer on its chat spans |
| 6 | 2 Monitor | Three Dash0 evals as YAML |
| 7 | 3 Detect | The broken agent, traffic, verdicts in Dash0 |
| 8 | 4 Bridge | Export, DuckDB SQL, human review, golden dataset |
| 9 | 5 Test | harness-evals config, baseline, flaky check |
| 10 | 6 Gate | GitHub Actions |
| 11 | 7 Verify | Before/after in DuckDB |
| 12 | 8 Automate | The Temporal workflow |
| 13 | – | Offline rehearsal, README, publish |

### Why both Dash0 Evals and harness-evals

The two tools solve different problems and work together:

**Dash0 Evals** (production monitoring)
- Judge every request as it arrives in production
- Catch regressions that affect real users
- Can't gate a deployment. You only see what traffic you actually receive
- Requires OpenTelemetry integration

**harness-evals** (CI gate)
- Test a controlled golden dataset of known failure cases before deploying
- Prevent known regressions from shipping
- Gate the deployment immediately, without waiting for production traffic
- Runs offline, no observability infrastructure needed

**In this sample:**
- Steps 1–4 set up Dash0 to judge production traffic
- Steps 5–8 export the failures Dash0 found and build a regression dataset
- Steps 9–10 use harness-evals in CI to gate deployments against that dataset
- Step 11 verifies the fix in production (Dash0 again)
- Step 12 automates the loop so new failures feed back into the dataset

Dash0 finds bugs, harness-evals keeps them out.

### Conventions

- Run every command from the repository root. The agent and the CLI both read `.env` from there, and the CLI refuses to run elsewhere.
- After step 3, `evals-gate` means `java -jar cli/target/evals-gate.jar`. Set it up once per terminal with `alias evals-gate='java -jar cli/target/evals-gate.jar'`.
- Rebuild after changing Java: `mvn -q package -DskipTests` from the root builds both jars (about 20 seconds after the first build).

## Step 1 – Install the tools

### Tools

| Tool | Version | Why | Install (macOS) |
| --- | --- | --- | --- |
| Java (JDK) | 21 or newer | builds and runs everything | `brew install openjdk@21` or SDKMAN: `sdk install java 21.0.9-tem` |
| Maven | 3.9 or newer | builds the two jars | `brew install maven` |
| uv | any recent | runs harness-evals with `uvx`, no virtualenv | `brew install uv` |
| dash0 CLI | current | applies evals, exports verdicts and spans | see the Dash0 CLI docs (Homebrew tap or release binary) |
| Temporal CLI | current | `temporal server start-dev` (step 12, optional) | `brew install temporal` |
| gh | current | creates the GitHub repo and sets secrets | `brew install gh` |
| git, curl | any |  | preinstalled |

You don't install DuckDB: the CLI jar includes DuckDB's JDBC driver and runs the SQL files itself. You don't install Python either: `uvx` downloads a private Python and the pinned harness-evals into its cache on first use.

Check the versions:

```bash
java -version          # 21 or higher
mvn -version           # 3.9 or higher, and "Java version: 21" or higher
uvx --version
dash0 --version
temporal --version
gh --version
```

If `mvn -version` reports an older Java than `java -version`, set `JAVA_HOME` to the JDK 21 path (`/usr/libexec/java_home -v 21` on macOS prints it).

Optional warm-up, so the first gate run on stage isn't a download:

```bash
uvx --from 'harness-evals[llm,harness]==0.29.1' harness-evals --help
```

### Accounts and keys

1. **OpenAI API key** with access to `gpt-4o-mini` (the agent) and `gpt-6.1-terra` (the CI judge), on an organization with prepaid credit. The API is billed separately from ChatGPT: without credit every call returns `429 You have no credits remaining`, and right after the first purchase the rate limits can stay at free-tier levels (10 requests a minute) for up to an hour. $5–10 is plenty for this sample.
2. **Dash0**: an organization, and an auth token that can both ingest data and query the API. Note the OTLP endpoint and the API endpoint for your region from the organization settings.
3. **GitHub**: log in once with the CLI.

```bash
gh auth login
```

Pick GitHub.com, HTTPS, and log in with a browser.

## Step 2 – Create the project skeleton

Create the folders in one go:

```bash
mkdir evals-gate && cd evals-gate
git init -b main
mkdir -p agent/src/main/java/dev/stilllies/agent agent/src/main/resources/policies \
         cli/src/main/java/dev/stilllies/cli cli/src/main/java/dev/stilllies/temporal \
         traffic dash0/evals sql evals .evals/baselines samples .github/workflows
```

### `.gitignore`

Build output, local exports and run results stay out of git. So does `.env`, which holds your keys.

```
.env
target/
data/
results/
evals/rehearsal.eval.yaml
.idea/
.vscode/
*.iml
```

### `.env.example`

The template for `.env`. Commit the example, never `.env` itself.

```bash
# Copy to .env and fill in. The agent and the CLI both read it when you run them from the repository root.
# Never commit .env.

# OpenAI: used by the agent, and as the judge model for harness-evals.
OPENAI_API_KEY=sk-...
AGENT_MODEL=gpt-4o-mini

# Dash0 OTLP ingress endpoint for your region (OTLP over HTTP), as shown in your Dash0 organization settings.
# Example: https://ingress.eu-west-1.aws.dash0.com
DASH0_OTLP_URL=https://ingress.eu-west-1.aws.dash0.com

# Dash0 API endpoint for your region, as shown in your Dash0 organization settings. Used by the dash0 CLI.
# Example: https://api.eu-west-1.aws.dash0.com
DASH0_API_URL=https://api.eu-west-1.aws.dash0.com

# A Dash0 auth token that can ingest data and read the API, created in your Dash0 organization settings.
DASH0_AUTH_TOKEN=auth_...

# The Dash0 dataset to send to and query. "default" unless you created another one.
DASH0_DATASET=default
```

Then make your own copy and fill in the real values:

```bash
cp .env.example .env
```

How `.env` reaches each program, since there's no Make to export it:

- **The agent** imports it with Spring Boot's `spring.config.import: optional:file:.env[.properties]` (step 4), so `${OPENAI_API_KEY}` and friends resolve as properties.
- **The CLI** reads it in `Env.java` (step 3) and passes the values to every program it starts: the dash0 CLI, harness-evals, git.
- **Real environment variables win** in both. That's how CI works without a `.env`, and how you override one value for one command, for example `AGENT_URL=http://localhost:9090/answer evals-gate traffic`.

The dash0 CLI reads `DASH0_API_URL`, `DASH0_AUTH_TOKEN` and `DASH0_DATASET` from its environment, so you don't need `dash0 login`.

### The parent `pom.xml`

An aggregator with no code: `mvn package` in the root builds both modules. It deliberately has no parent of its own, so the agent can use Spring Boot's parent and the CLI stays free of Spring's dependency management.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Builds both jars from the repository root:
    mvn -q package -DskipTests
  agent/target/support-agent.jar   the Spring Boot support agent
  cli/target/evals-gate.jar        every other step: export, DuckDB, gate, Temporal, rehearsal
-->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>dev.stilllies</groupId>
  <artifactId>evals-gate</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <name>evals-gate</name>
  <description>Sample for the talk "When Your LLM Passes Every Check And Still Lies".</description>

  <modules>
    <module>agent</module>
    <module>cli</module>
  </modules>
</project>
```

Don't build yet: the modules come in steps 3 and 4.

## Step 3 – The evals-gate command line

Every step except running the agent is a subcommand of one jar: the CLI starts the dash0 CLI and harness-evals as processes, runs the SQL files through DuckDB's JDBC driver, sends test traffic with the JDK's HTTP client, and hosts the Temporal worker.

| Class | Job |
| --- | --- |
| `Main` | the command list and dispatch |
| `Args` | `--name value` options |
| `Env` | environment variables, falling back to `.env` |
| `Proc` | starts external programs with `.env` applied |
| `Duck` | runs a `.sql` file statement by statement over JDBC and prints the results |
| `Steps` | every step of the demo, as plain static methods |
| `RehearsalModel` | a stand-in OpenAI endpoint for offline rehearsal (explained in step 13) |

### `cli/pom.xml`

DuckDB JDBC, the Temporal SDK, Jackson (for JSON in the rehearsal model and the traffic sender) and slf4j-simple. The shade plugin makes one runnable jar.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>dev.stilllies</groupId>
  <artifactId>evals-gate-cli</artifactId>
  <version>1.0.0</version>
  <name>evals-gate command line</name>
  <description>Every step of the demo as one jar: java -jar cli/target/evals-gate.jar help</description>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <duckdb.version>2.0.0.1</duckdb.version>
    <temporal.version>1.40.0</temporal.version>
    <jackson.version>2.19.2</jackson.version>
    <slf4j.version>2.0.17</slf4j.version>
  </properties>

  <dependencies>
    <!-- All SQL in sql/ runs through DuckDB's JDBC driver: no DuckDB CLI needed -->
    <dependency>
      <groupId>org.duckdb</groupId>
      <artifactId>duckdb_jdbc</artifactId>
      <version>${duckdb.version}</version>
    </dependency>
    <!-- The incident workflow -->
    <dependency>
      <groupId>io.temporal</groupId>
      <artifactId>temporal-sdk</artifactId>
      <version>${temporal.version}</version>
    </dependency>
    <!-- JSON for the rehearsal model and the traffic sender -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>${slf4j.version}</version>
    </dependency>
  </dependencies>

  <build>
    <finalName>evals-gate</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <!-- One runnable jar with every dependency -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>dev.stilllies.cli.Main</mainClass>
                </transformer>
                <!-- gRPC (used by the Temporal client) finds its transports through service files -->
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

The `ServicesResourceTransformer` isn't optional: without it the shaded jar loses gRPC's service files and the Temporal client fails at startup. The signature excludes prevent an "Invalid signature file digest" error from signed dependencies.

All Java files below go in `cli/src/main/java/dev/stilllies/cli/`.

### `Env.java`

```java
package dev.stilllies.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings from the environment, falling back to .env in the repository root.
 *
 * <p>The real environment wins, so CI (which has no .env) and one-off overrides
 * ({@code AGENT_URL=... java -jar ...}) both work. Every subprocess (dash0, harness-evals, git)
 * gets the merged settings.
 */
public final class Env {

  private static final Map<String, String> DOTENV = load(Path.of(".env"));

  private Env() {}

  public static String get(String name) {
    String value = System.getenv(name);
    if (value != null && !value.isBlank()) {
      return value;
    }
    return DOTENV.get(name);
  }

  public static String get(String name, String fallback) {
    String value = get(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  public static boolean hasDotenv() {
    return Files.exists(Path.of(".env"));
  }

  /** The values from .env that the real environment doesn't already set. */
  public static Map<String, String> dotenvOnly() {
    Map<String, String> out = new LinkedHashMap<>();
    DOTENV.forEach((k, v) -> {
      if (System.getenv(k) == null) {
        out.put(k, v);
      }
    });
    return out;
  }

  /** KEY=value lines; blank lines and # comments are skipped; optional quotes are removed. */
  static Map<String, String> load(Path file) {
    Map<String, String> values = new LinkedHashMap<>();
    if (!Files.exists(file)) {
      return values;
    }
    try {
      for (String line : Files.readAllLines(file)) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
          continue;
        }
        if (trimmed.startsWith("export ")) {
          trimmed = trimmed.substring(7).strip();
        }
        int eq = trimmed.indexOf('=');
        String key = trimmed.substring(0, eq).strip();
        String value = trimmed.substring(eq + 1).strip();
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
          value = value.substring(1, value.length() - 1);
        }
        values.put(key, value);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file, e);
    }
    return values;
  }
}
```

### `Args.java`

```java
package dev.stilllies.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Positional arguments plus --name value (or --name=value) options. */
public final class Args {

  private final List<String> positional = new ArrayList<>();
  private final Map<String, String> options = new HashMap<>();

  public Args(String[] args) {
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (a.startsWith("--")) {
        String name = a.substring(2);
        int eq = name.indexOf('=');
        if (eq >= 0) {
          options.put(name.substring(0, eq), name.substring(eq + 1));
        } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
          options.put(name, args[++i]);
        } else {
          options.put(name, "true");
        }
      } else {
        positional.add(a);
      }
    }
  }

  public String positional(int index, String fallback) {
    return index < positional.size() ? positional.get(index) : fallback;
  }

  /** The arguments after the first {@code from} positional ones. */
  public Args shift(int from) {
    List<String> rest = new ArrayList<>(positional.subList(Math.min(from, positional.size()), positional.size()));
    options.forEach((k, v) -> {
      rest.add("--" + k);
      rest.add(v);
    });
    return new Args(rest.toArray(String[]::new));
  }

  public String option(String name, String fallback) {
    String v = options.get(name);
    return v == null || v.isBlank() ? fallback : v;
  }

  public int intOption(String name, int fallback) {
    return Integer.parseInt(option(name, Integer.toString(fallback)));
  }

  public String required(String name) {
    String v = options.get(name);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException("missing --" + name);
    }
    return v;
  }
}
```

### `Proc.java`

```java
package dev.stilllies.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Runs external programs (dash0, harness-evals, git, gh) from the repository root, with .env applied. */
public final class Proc {

  private Proc() {}

  /** Runs and fails on a non-zero exit. */
  public static void run(List<String> command) {
    int exit = exitCode(command, Map.of(), null);
    if (exit != 0) {
      throw new IllegalStateException(String.join(" ", command) + " exited with " + exit);
    }
  }

  /** Runs and returns the exit code. Output goes to the console, or to {@code logFile} when given. */
  public static int exitCode(List<String> command, Map<String, String> extraEnv, Path logFile) {
    System.err.println("$ " + String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().putAll(Env.dotenvOnly());
    pb.environment().putAll(extraEnv);
    if (logFile != null) {
      pb.redirectErrorStream(true).redirectOutput(logFile.toFile());
    } else {
      pb.inheritIO();
    }
    try {
      return pb.start().waitFor();
    } catch (IOException e) {
      throw new IllegalStateException("could not start " + command.get(0) + ": is it installed and on your PATH?", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running " + command.get(0), e);
    }
  }

  /** Runs, sending stdout to a file (the dash0 CLI's CSV exports). */
  public static void toFile(List<String> command, Path out) {
    System.err.println("$ " + String.join(" ", command) + " > " + out);
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().putAll(Env.dotenvOnly());
    pb.redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
    try {
      int exit = pb.start().waitFor();
      if (exit != 0) {
        throw new IllegalStateException(command.get(0) + " exited with " + exit);
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not start " + command.get(0) + ": is it installed and on your PATH?", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running " + command.get(0), e);
    }
  }

  /** Runs and returns stdout, or "" on a non-zero exit. */
  public static String output(List<String> command) {
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
    pb.environment().putAll(Env.dotenvOnly());
    try {
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes());
      return p.waitFor() == 0 ? out : "";
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  /** True when an executable with this name is on the PATH. */
  public static boolean onPath(String name) {
    String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    for (String dir : path.split(File.pathSeparator)) {
      if (Files.isExecutable(Path.of(dir, name)) || Files.isExecutable(Path.of(dir, name + ".exe"))) {
        return true;
      }
    }
    return false;
  }
}
```

### `Duck.java`

JDBC executes one statement at a time, so `Duck` splits each `.sql` file on semicolons, skipping semicolons inside quotes and `--` comments, and prints the result of every `SELECT` as a table. All steps share one database file, `data/evals.duckdb`, so later SQL files see the tables and views earlier ones created.

```java
package dev.stilllies.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the SQL files in sql/ through DuckDB's JDBC driver, statement by statement, and prints the
 * result of every SELECT as a table. The SQL is the same you'd paste into the DuckDB CLI.
 */
public final class Duck implements AutoCloseable {

  /** The database file every step shares, so later steps see earlier tables and views. */
  public static final String DATABASE = "data/evals.duckdb";

  private final Connection connection;

  private Duck(String url) {
    try {
      this.connection = DriverManager.getConnection(url);
    } catch (SQLException e) {
      throw new IllegalStateException("could not open " + url, e);
    }
  }

  /** The shared database file in data/. */
  public static Duck file() {
    try {
      Files.createDirectories(Path.of("data"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new Duck("jdbc:duckdb:" + DATABASE);
  }

  /** A throwaway in-memory database, for queries that only read files. */
  public static Duck memory() {
    return new Duck("jdbc:duckdb:");
  }

  public Connection connection() {
    return connection;
  }

  /** Runs one SQL file. With print, every SELECT's result is printed as a table. */
  public void runFile(String path, boolean print) {
    String sql;
    try {
      sql = Files.readString(Path.of(path));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }
    for (String statement : split(sql)) {
      run(statement, print);
    }
  }

  public void run(String statement, boolean print) {
    try (Statement st = connection.createStatement()) {
      boolean hasResult = st.execute(statement);
      if (print && hasResult && isQuery(statement)) {
        try (ResultSet rs = st.getResultSet()) {
          printTable(rs);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("DuckDB: " + e.getMessage() + "\nin statement:\n" + statement, e);
    }
  }

  public long queryLong(String sql) {
    try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException("DuckDB: " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      // nothing useful to do
    }
  }

  /** Splits on semicolons outside quotes and -- comments. Drops statements that are only comments. */
  static List<String> split(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean single = false;
    boolean dbl = false;
    boolean comment = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (comment) {
        current.append(c);
        if (c == '\n') {
          comment = false;
        }
        continue;
      }
      if (!single && !dbl && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        comment = true;
        current.append(c);
        continue;
      }
      if (c == '\'' && !dbl) {
        single = !single;
      } else if (c == '"' && !single) {
        dbl = !dbl;
      }
      if (c == ';' && !single && !dbl) {
        add(out, current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    add(out, current.toString());
    return out;
  }

  private static void add(List<String> out, String statement) {
    if (!stripComments(statement).isBlank()) {
      out.add(statement.strip());
    }
  }

  private static String stripComments(String statement) {
    StringBuilder b = new StringBuilder();
    for (String line : statement.split("\n")) {
      if (!line.strip().startsWith("--")) {
        b.append(line).append('\n');
      }
    }
    return b.toString();
  }

  private static boolean isQuery(String statement) {
    String s = stripComments(statement).strip().toUpperCase();
    return s.startsWith("SELECT") || s.startsWith("WITH") || s.startsWith("FROM");
  }

  private static void printTable(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<String[]> rows = new ArrayList<>();
    String[] header = new String[n];
    for (int i = 0; i < n; i++) {
      header[i] = md.getColumnLabel(i + 1);
    }
    rows.add(header);
    while (rs.next()) {
      String[] row = new String[n];
      for (int i = 0; i < n; i++) {
        Object v = rs.getObject(i + 1);
        String s = v == null ? "NULL" : v.toString().replace('\n', ' ');
        row[i] = s.length() > 80 ? s.substring(0, 77) + "..." : s;
      }
      rows.add(row);
    }
    int[] width = new int[n];
    for (String[] row : rows) {
      for (int i = 0; i < n; i++) {
        width[i] = Math.max(width[i], row[i].length());
      }
    }
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < n; i++) {
      line.append(i == 0 ? "" : "-+-").append("-".repeat(width[i]));
    }
    for (int r = 0; r < rows.size(); r++) {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < n; i++) {
        b.append(i == 0 ? "" : " | ").append(String.format("%-" + width[i] + "s", rows.get(r)[i]));
      }
      System.out.println(b.toString().stripTrailing());
      if (r == 0) {
        System.out.println(line);
      }
    }
    System.out.println("(" + (rows.size() - 1) + " rows)");
    System.out.println();
  }
}
```

### `Steps.java`

Every step of the demo, as a static method. The command line calls them, and in step 12 the Temporal activities call the same methods, so there's one implementation of each step.

```java
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

  /** Creates or updates the three evals in Dash0 from dash0/evals/. */
  public static void dash0Evals() {
    Proc.run(List.of("dash0", "--experimental", "apply", "-f", "dash0/evals/"));
  }

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
```

### `Main.java`

```java
package dev.stilllies.cli;

import dev.stilllies.temporal.TemporalCommands;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every step of the demo, in the order you use them. Run from the repository root:
 *
 * <pre>
 *   java -jar cli/target/evals-gate.jar help
 * </pre>
 */
public final class Main {

  private static final String HELP = """
      usage: java -jar cli/target/evals-gate.jar <command> [options]   (run from the repository root)

      setup
        check                         check every tool and setting the demo needs
      phase 1 and 2: the agent and the Dash0 evals
        dash0-evals                   create or update the three evals in Dash0 from dash0/evals/
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
        case "dash0-evals" -> Steps.dash0Evals();
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
```

Exit codes follow the harness-evals convention: 0 success, 1 a failure (including a failed gate), 2 a usage or configuration error. `gate`, `eval` and `eval-baseline` pass harness-evals' own exit code straight through, which is what makes the CLI usable as a CI step.

### Two files that come later

`Main` refers to `RehearsalModel` (in this package) and `TemporalCommands` (in `dev.stilllies.temporal`). Their code and explanation are in steps 12 and 13. To build now, create those nine files first by copying them from steps 12 and 13; they compile without a Temporal server or a model running.

### Build and try it

After step 4 (the build needs both modules), from the repository root:

```bash
mvn -q package -DskipTests
alias evals-gate='java -jar cli/target/evals-gate.jar'
evals-gate help
evals-gate check
```

The first build downloads Spring Boot, Spring AI, Temporal and DuckDB from Maven Central and takes a few minutes. `check` lists anything missing; once `.env` is filled in and both jars exist it prints `all good`.

## Step 4 – Build the support agent with Spring AI

The agent is a Spring Boot 4.1 application with Spring AI 2.0. It has one endpoint, `POST /answer`, and gives the model one tool, `lookup_policy`, which returns the text of a policy file. Spring AI's `ChatClient` runs the tool loop: the first model call asks for the tool, Spring AI runs it, and the second model call answers with the policy text in its input. Each model call is its own `chat` span, exactly the shape the evals need.

### `agent/pom.xml`

The agent uses Spring Boot's parent and Spring AI's BOM. Three starters: Spring MVC for the endpoint, Spring AI's OpenAI model, and Spring Boot's OpenTelemetry starter for traces.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.2</version>
    <relativePath/>
  </parent>

  <groupId>dev.stilllies</groupId>
  <artifactId>support-agent</artifactId>
  <version>1.0.0</version>
  <name>evals-gate support agent</name>
  <description>A billing support agent that answers from policy documents (Spring AI).</description>

  <properties>
    <java.version>21</java.version>
    <spring-ai.version>2.0.2</spring-ai.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- POST /answer -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <!-- ChatClient, tool calling, and the OpenAI chat model -->
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <!-- Micrometer Observation to OpenTelemetry spans, exported over OTLP to Dash0 -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-opentelemetry</artifactId>
      <exclusions>
        <!-- traces only: without this, metrics are pushed to localhost:4318 every minute -->
        <exclusion>
          <groupId>io.micrometer</groupId>
          <artifactId>micrometer-registry-otlp</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
  </dependencies>

  <build>
    <finalName>support-agent</finalName>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

### The three policies

They go in `agent/src/main/resources/policies/`, so they're on the classpath. `refunds.md` holds the key fact for the whole talk, the **14-day** window:

```
REFUND-POLICY-2026-10

Monthly plans can be cancelled at any time. Cancellation stops the next renewal; the current month is not refunded.

Annual plans can be refunded in full within 14 days of purchase.
After 14 days, annual plans are not refunded. Instead, the unused full months are credited to the account and can be used for any plan.

Refunds go back to the original payment method and arrive within 5 to 10 business days.
```

`shipping.md`:

```
SHIPPING-POLICY-2026-10

Hardware orders ship within 2 business days from our warehouse in Rotterdam.
Delivery inside the EU takes 3 to 5 business days. Delivery outside the EU takes 7 to 14 business days.
Shipping is free for orders over 100 euro. Below that, shipping costs 9 euro.
```

`account.md`:

```
ACCOUNT-POLICY-2026-10

Account owners can add and remove users under Settings, Members.
Each plan includes 5 users. Extra users cost 8 euro per user per month.
Passwords can be reset from the sign-in page. Accounts are locked for 15 minutes after 5 failed sign-in attempts.
```

Shipping and account give the Topic eval something to tell apart, so the DuckDB report can show that only billing fails.

The Java files go in `agent/src/main/java/dev/stilllies/agent/`.

### `PolicyTools.java`

The tool, written as a plain annotated method. Spring AI builds the JSON schema the model sees from the method signature, including the three allowed topics from the enum. The object also records each call, because harness-evals needs the tool calls and the retrieved text in the HTTP response.

```java
package dev.stilllies.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.ClassPathResource;

/** The one tool the model gets. It also records each call, so /answer can return them. */
public class PolicyTools {

  /** Lower-case constants, so the JSON schema the model sees says "refunds", not "REFUNDS". */
  public enum Topic { refunds, shipping, account }

  public record Call(String name, Map<String, Object> input, String output) {}

  private final List<Call> calls = new CopyOnWriteArrayList<>();

  @Tool(name = "lookup_policy", description = "Returns the current Acme Cloud policy text for a topic. "
      + "refunds: refunds, cancellations, stopping or changing a monthly or annual plan, and account credit. "
      + "shipping: hardware orders, delivery times and shipping costs. "
      + "account: users, sign-in, passwords and locked accounts.")
  public String lookupPolicy(
      @ToolParam(description = "refunds for anything about money back, cancelling or stopping a plan; "
          + "shipping for deliveries; account for users and sign-in") Topic topic) {
    String text = read(topic);
    calls.add(new Call("lookup_policy", Map.of("topic", topic.name()), text));
    return text;
  }

  public List<Call> calls() {
    return List.copyOf(calls);
  }

  public List<String> context() {
    return calls.stream().map(Call::output).toList();
  }

  private static String read(Topic topic) {
    ClassPathResource resource = new ClassPathResource("policies/" + topic.name() + ".md");
    if (!resource.exists()) {
      return "No policy found for this topic.";
    }
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
```

### `AnswerController.java`

```java
package dev.stilllies.agent;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /answer takes {"input": "<question>"} and returns the answer, the tool calls the model
 * made, and the policy text it retrieved. That is the shape harness-evals reads in CI.
 *
 * <p>agent.variant=broken reproduces the incident from the talk: a system prompt that promises a
 * generous refund window, so the model contradicts the policy it retrieves.
 */
@RestController
public class AnswerController {

  static final Map<String, String> SYSTEM_PROMPTS = Map.of(
      "good",
      "You are the billing support agent for Acme Cloud. "
          + "Always call lookup_policy before answering. "
          + "Questions about cancelling, stopping or changing a plan are refund questions. "
          + "If the customer's details don't settle whether a rule applies, such as a purchase date "
          + "given as 'last month', explain the rule and ask for the missing detail instead of deciding. "
          + "Answer only with facts from the policy text the tool returns. "
          + "Do not add advice, offers, contact details or promises that the policy text does not state. "
          + "If the policy does not cover the question, say so. "
          + "Answer in at most three sentences.",
      "broken",
      "You are the friendly billing support agent for Acme Cloud. "
          + "Always call lookup_policy before answering. "
          + "Keep customers happy: our refund window is a generous 30 days on every plan. "
          + "Answer in at most three sentences.");

  public record Question(String input) {}

  public record Answer(String answer, List<PolicyTools.Call> tool_calls, List<String> context, String variant) {}

  private final ChatClient chatClient;
  private final String variant;
  private final String model;

  public AnswerController(
      ChatClient.Builder builder,
      @Value("${agent.variant}") String variant,
      @Value("${spring.ai.openai.chat.model}") String model) {
    if (!SYSTEM_PROMPTS.containsKey(variant)) {
      throw new IllegalArgumentException("agent.variant must be good or broken, not " + variant);
    }
    this.chatClient = builder.build();
    this.variant = variant;
    this.model = model;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok", "variant", variant, "model", model);
  }

  @PostMapping("/answer")
  public Answer answer(@RequestBody Question question) {
    // A new tools object per request, so it records only this request's tool calls.
    PolicyTools tools = new PolicyTools();
    // ChatClient runs the tool loop: the first model call asks for lookup_policy,
    // the next one answers with the policy text in its input. Each call is its own chat span.
    String answer = chatClient.prompt()
        .system(SYSTEM_PROMPTS.get(variant))
        .user(question.input())
        .tools(tools)
        .call()
        .content();
    // What the answer may legitimately draw on: the retrieved policy text, and what the customer said.
    // harness-evals' faithfulness checks every claim against this list, so without the question a correct
    // answer that repeats "you bought it 40 days ago" would count that as an unsupported claim.
    List<String> context = new java.util.ArrayList<>(tools.context());
    context.add("Customer question: " + question.input());
    return new Answer(answer, tools.calls(), context, variant);
  }
}
```

Each line of the good prompt beyond "call the tool" exists because the first real CI run failed without it (step 9 has the details): cancellation questions went to the account policy, answers padded with advice the policy doesn't state failed faithfulness, and "last month" got a yes-or-no instead of a question back. The tool description in `PolicyTools` says which topic covers what for the same reason.

Why it's shaped like this:

- **The request body is `{"input": ...}`** because that's what harness-evals sends to an HTTP target by default.
- **The response has `tool_calls` and `context`** next to `answer`. harness-evals reads them through JSON paths: `tool_calls` feeds `tool_correctness`, `context` feeds `faithfulness`. The record component is literally named `tool_calls` so the JSON key matches.
- **The broken variant** plants the 30-day claim in the system prompt. The model still retrieves the correct policy, then contradicts it. In the talk a model swap caused this; on stage the prompt makes it reproducible. Say so when you demo it.

### `SupportAgentApplication.java`

```java
package dev.stilllies.agent;

import io.micrometer.observation.ObservationFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * A small billing support agent that answers from policy documents.
 *
 * <p>Run it from the repository root (it reads .env from there):
 *
 * <pre>
 *   java -jar agent/target/support-agent.jar                                      fixed, no telemetry
 *   java -jar agent/target/support-agent.jar --spring.profiles.active=dash0       fixed, traces to Dash0
 *   java -jar agent/target/support-agent.jar --spring.profiles.active=dash0 --agent.variant=broken
 * </pre>
 */
@SpringBootApplication
public class SupportAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(SupportAgentApplication.class, args);
  }

  /** Puts the prompt and the answer on every chat span, where the Dash0 evals read them. */
  @Bean
  ObservationFilter genAiMessagesObservationFilter() {
    return new GenAiMessagesObservationFilter();
  }
}
```

The `GenAiMessagesObservationFilter` it registers is the subject of step 5. Create that file too before you build.

### `agent/src/main/resources/application.yaml`

```yaml
# Run the agent from the repository root: the import below reads .env from there,
# so OPENAI_API_KEY, AGENT_MODEL and the DASH0_* settings come from the same file as the CLI's.
spring:
  application:
    name: support-agent            # becomes service.name on every span
  config:
    import: optional:file:.env[.properties]
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        model: ${AGENT_MODEL:gpt-4o-mini}
    tools:
      observations:
        include-content: true      # tool arguments and result on the execute_tool span

server:
  port: ${PORT:8080}

# good or broken; override with --agent.variant=broken
agent:
  variant: good

management:
  tracing:
    sampling:
      probability: 1.0             # the default is 0.1: keep every trace for the demo
  opentelemetry:
    resource-attributes:
      service.version: ${SERVICE_VERSION:dev}
      deployment.environment.name: ${DEPLOYMENT_ENVIRONMENT:demo}
```

- `optional:file:.env[.properties]` reads `.env` as a properties file. `optional:` means CI, which has no `.env`, still starts.
- In Spring AI 2.0 the model property is `spring.ai.openai.chat.model` (no `options` level).
- Sampling defaults to 10 percent in Spring Boot. At that rate most of your demo traffic would never reach Dash0.

### `traffic/questions.txt`

Ten billing questions (most about refunds after 14 days), three shipping and four account questions, one per line:

```
I bought the annual plan 40 days ago. Can I still get a refund?
I signed up for the annual plan 20 days ago and want my money back. Is that possible?
Can I get a refund on my annual plan? I bought it 3 weeks ago.
I bought the yearly plan last month by mistake. Can you refund it?
How long do I have to ask for a refund on an annual subscription?
I bought the annual plan 10 days ago. Can I still get a full refund?
I cancelled my monthly plan halfway through the month. Do I get the rest of the month back?
It has been 25 days since I paid for the annual plan. What are my options if I want to stop?
What happens to my money if I cancel an annual plan after two months?
When will my refund show up on my card?
How long does shipping to Germany take?
Do I pay for shipping on a 60 euro order?
How long does delivery to the United States take?
How many users are included in my plan?
How much does an extra user cost?
My account is locked after too many sign-in attempts. How long do I have to wait?
How do I add a colleague to my account?
```

### First test, without telemetry

Once step 5's filter exists, build and start the fixed agent without the `dash0` profile, so nothing is exported yet:

```bash
mvn -q package -DskipTests
java -jar agent/target/support-agent.jar
```

In a second terminal:

```bash
curl -s localhost:8080/health
curl -s localhost:8080/answer -H 'content-type: application/json' \
  -d '{"input": "I bought the annual plan 40 days ago. Can I still get a refund?"}'
```

Expected: `/health` returns `{"status":"ok","variant":"good",...}`, and the answer says annual plans are refundable within 14 days, with `tool_calls` showing one `lookup_policy` call with `{"topic":"refunds"}`. Restart with `--agent.variant=broken` and ask again: now it promises a 30-day window. Stop with Ctrl+C.

## Step 5 – Put the prompt and answer on the span, and send traces to Dash0

Spring AI already creates a span per model call, named like `chat gpt-4o-mini`, with `gen_ai.operation.name`, the model, token counts and finish reasons. What it does **not** put on the span is the text: the prompt and the completion are only logged (`spring.ai.chat.observations.log-prompt`). The OpenTelemetry Java agent's OpenAI instrumentation has the same gap: it sends message content as log events. The Dash0 evals read span attributes, so a span without the text gives the judge nothing to read.

The fix is one small class. Micrometer runs every `ObservationFilter` just before an observation stops, and Micrometer Tracing copies the observation's key values onto the span. So the filter adds two key values to each chat observation, in the OpenTelemetry GenAI format:

- `gen_ai.input.messages`: a JSON array of every message sent to the model, each `{"role": ..., "parts": [...]}`. The policy text arrives as a `tool` message with a part of type `tool_call_response`.
- `gen_ai.output.messages`: the reply, with `"finish_reason":"stop"` on a final answer or `"tool_calls"` when the model asks for the tool.

### `GenAiMessagesObservationFilter.java`

```java
package dev.stilllies.agent;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * Adds gen_ai.input.messages and gen_ai.output.messages to every chat model span, in the format
 * of the OpenTelemetry GenAI semantic conventions: a JSON array of {"role": ..., "parts": [...]}.
 *
 * <p>Spring AI records the model, tokens and finish reasons on the span, but it only logs the
 * prompt and completion text. The Dash0 evals read the text from span attributes, so this filter
 * puts it there. Micrometer Tracing turns high-cardinality key values into span attributes.
 *
 * <p>This sends customer questions and answers to your observability backend. Fine for a demo;
 * in production, decide deliberately and scrub what you must.
 */
public class GenAiMessagesObservationFilter implements ObservationFilter {

  @Override
  public Observation.Context map(Observation.Context context) {
    if (!(context instanceof ChatModelObservationContext chat)) {
      return context;
    }
    chat.addHighCardinalityKeyValue(KeyValue.of("gen_ai.input.messages", inputMessages(chat.getRequest().getInstructions())));
    ChatResponse response = chat.getResponse();
    if (response != null) {
      chat.addHighCardinalityKeyValue(KeyValue.of("gen_ai.output.messages", outputMessages(response)));
    }
    return chat;
  }

  static String inputMessages(List<Message> messages) {
    List<String> out = new ArrayList<>();
    for (Message message : messages) {
      if (message instanceof SystemMessage system) {
        out.add(message("system", List.of(textPart(system.getText())), null));
      } else if (message instanceof UserMessage user) {
        out.add(message("user", List.of(textPart(user.getText())), null));
      } else if (message instanceof AssistantMessage assistant) {
        out.add(message("assistant", assistantParts(assistant), null));
      } else if (message instanceof ToolResponseMessage tool) {
        List<String> parts = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse r : tool.getResponses()) {
          parts.add("{\"response\":" + str(r.responseData()) + ",\"id\":" + str(r.id()) + ",\"type\":\"tool_call_response\"}");
        }
        out.add(message("tool", parts, null));
      }
    }
    return "[" + String.join(",", out) + "]";
  }

  static String outputMessages(ChatResponse response) {
    List<String> out = new ArrayList<>();
    for (Generation generation : response.getResults()) {
      String finish = generation.getMetadata().getFinishReason();
      out.add(message("assistant", assistantParts(generation.getOutput()),
          finish == null ? null : finish.toLowerCase(Locale.ROOT)));
    }
    return "[" + String.join(",", out) + "]";
  }

  private static List<String> assistantParts(AssistantMessage assistant) {
    List<String> parts = new ArrayList<>();
    if (assistant.getText() != null && !assistant.getText().isEmpty()) {
      parts.add(textPart(assistant.getText()));
    }
    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
      parts.add("{\"arguments\":" + str(call.arguments()) + ",\"name\":" + str(call.name())
          + ",\"id\":" + str(call.id()) + ",\"type\":\"tool_call\"}");
    }
    return parts;
  }

  private static String textPart(String text) {
    return "{\"content\":" + str(text) + ",\"type\":\"text\"}";
  }

  /** Compact JSON, no spaces: the Dash0 evals match on the literal "finish_reason":"stop". */
  private static String message(String role, List<String> parts, String finishReason) {
    String json = "{\"role\":" + str(role) + ",\"parts\":[" + String.join(",", parts) + "]";
    if (finishReason != null) {
      json += ",\"finish_reason\":" + str(finishReason);
    }
    return json + "}";
  }

  private static String str(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
```

Details that matter:

- **Spring AI 2.0 keeps the tool loop outside the model.** `OpenAiChatModel` makes one call per observation, and `ChatClient`'s tool-calling advisor runs the tool and calls the model again. That's why you get two chat spans per request, and why the second one's input contains the `tool` message with the policy text.
- **Spring AI reports finish reasons in upper case** (`STOP`, `TOOL_CALLS`). The filter lower-cases them to match the OpenTelemetry format and the eval filter in step 6.
- **The JSON is compact**, with no spaces after colons, because the evals in step 6 filter on the literal string `"finish_reason":"stop"`.
- **The JSON shape matches what `sql/02_cases.sql` parses in step 8**: the user question at `parts[1] ->> '$.content'`, the policy at `parts[1] ->> '$.response'`, the answer at `$[0].parts[0].content`. This was checked by running that SQL on the filter's output.

### `agent/src/main/resources/application-dash0.yaml`

The OTLP exporter lives in a profile, so the agent exports only when you ask it to. Spring Boot creates no exporter when no endpoint is set, which keeps CI and rehearsal runs out of Dash0.

```yaml
# Active with --spring.profiles.active=dash0: export traces to Dash0 over OTLP/HTTP.
# Without this profile no exporter is created, so CI and rehearsal runs send nothing.
management:
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${DASH0_OTLP_URL}/v1/traces
          headers:
            Authorization: Bearer ${DASH0_AUTH_TOKEN}
            Dash0-Dataset: ${DASH0_DATASET:default}
```

In Spring Boot 4 the properties moved to `management.opentelemetry.tracing.export.otlp.*` (Boot 3 used `management.otlp.tracing.*`). The endpoint is the full traces URL, so `/v1/traces` is appended to the base OTLP URL from `.env`.

### Run it and check in Dash0

```bash
mvn -q package -DskipTests
java -jar agent/target/support-agent.jar --spring.profiles.active=dash0
```

In a second terminal, send one question:

```bash
curl -s localhost:8080/answer -H 'content-type: application/json' \
  -d '{"input": "How long does shipping to Germany take?"}'
```

In Dash0, open the tracing view, filter on `service.name = support-agent`, and open the newest trace. You should see:

1. `http post /answer`, the server span
2. the ChatClient span (`gen_ai.operation.name` = `framework`)
3. `chat gpt-4o-mini`, the first model call. Its output asks for `lookup_policy`.
4. the `execute_tool` span for `lookup_policy`, with its arguments and result
5. `chat gpt-4o-mini`, the second model call. Its input contains the `tool` message with `SHIPPING-POLICY-2026-10`, and its output the answer with `"finish_reason":"stop"`.

Click the last chat span and check that `gen_ai.input.messages` and `gen_ai.output.messages` are in its attributes. That span is the one the evals judge; the `finish_reason` filter in step 6 skips the first chat span, which is only a tool request.

If the chat spans lack the two attributes, the filter bean isn't registered: check the `@Bean` method in `SupportAgentApplication`. If you see only some of your requests, check `management.tracing.sampling.probability`. Stop the agent with Ctrl+C.

## Step 6 – Define the three Dash0 evals as code

This is the production monitoring half. Dash0 will judge every request in real-time and catch regressions as they happen. Later (steps 9–10), harness-evals will gate new code before it reaches production.

Create the evals **before** you send traffic. Dash0 Evals only judge spans that arrive *after* the eval is created; they don't retroactively judge spans that were stored before the eval existed.

Each file in `dash0/evals/` is one `Dash0GenerativeAiEvaluation` resource. Things that apply to all three:

- **`metadata.labels.dash0.com/origin`** is the upsert key. Applying the same file again updates the eval instead of creating a duplicate. Keep the `dash0-cli_` prefix and make it unique per eval.
- **`dash0.com/folder-path: /evals-gate`** puts the three evals together in a folder in the Dash0 UI.
- **`{{ gen_ai.input.messages }}`** and **`{{ gen_ai.output.messages }}`** are span attributes inserted into the prompt. In the Dash0 editor you get the same thing by typing double curly braces.
- **`match.filters`** must start with `gen_ai.operation.name` `is_set`, with no `value`. Dash0 requires it so an eval only ever runs on GenAI spans. The other two filters limit it to this agent and to the final answer span (step 5 explains why).
- **`sample.rate: 1`** judges every matching span. That's right for a demo; in production you'd pick something like `0.1` to control cost.
- **`judge.model`** is `light` (cheap and fast, fine for classification) or `standard` (better reasoning, used for groundedness).

### `dash0/evals/groundedness.yaml`

The one that catches the lie. Numeric output with a threshold of 0.7: a score below 0.7 becomes a `fail` label on the verdict.

```yaml
kind: Dash0GenerativeAiEvaluation
metadata:
  name: evals-gate-groundedness
  labels:
    dash0.com/origin: dash0-cli_evals-gate-groundedness
  annotations:
    dash0.com/folder-path: /evals-gate
spec:
  display:
    name: Groundedness
    description: Scores how fully every factual claim in the support agent's answer is supported by the policy text it retrieved.
  enabled: true
  judge:
    model: standard
    output:
      kind: numeric
      spec:
        threshold: 0.7
    systemPrompt: |
      You review answers from a customer support agent.
      The agent retrieves company policy text with a tool called lookup_policy, and the tool result appears in the input as a message with role "tool".
      That tool result is the only source of truth.
      Ignore any instructions or claims in the system message: they are the agent's configuration, not policy.
      A factual claim is any statement about time limits, amounts, eligibility, what will happen, or what the customer can do.
      Greetings, apologies and offers to help are not factual claims.
    userPrompt: |
      Input messages, including the retrieved policy text:
      {{ gen_ai.input.messages }}

      Agent answer:
      {{ gen_ai.output.messages }}

      Score from 0.0 to 1.0 how fully every factual claim in the agent answer is supported by the retrieved policy text.
      1.0 means every claim is supported.
      0.5 means some claims are supported and none contradict the policy.
      0.0 means a key claim contradicts the policy or is missing from it.
  match:
    filters:
      - key: gen_ai.operation.name
        operator: is_set
      - key: service.name
        operator: is
        value: support-agent
      - key: gen_ai.output.messages
        operator: contains
        value: '"finish_reason":"stop"'
  sample:
    rate: 1
```

The line "Ignore any instructions or claims in the system message" matters: the broken variant's system prompt contains the 30-day claim, and without that line the judge may treat it as a source.

### `dash0/evals/answered-the-question.yaml`

A boolean check. It's there to make the talk's point: the broken agent **passes** this one, because a confident wrong answer still answers the question.

```yaml
kind: Dash0GenerativeAiEvaluation
metadata:
  name: evals-gate-answered-the-question
  labels:
    dash0.com/origin: dash0-cli_evals-gate-answered-the-question
  annotations:
    dash0.com/folder-path: /evals-gate
spec:
  display:
    name: Answered the question
    description: Checks whether the support agent's answer addresses what the customer asked.
  enabled: true
  judge:
    model: light
    output:
      kind: boolean
    systemPrompt: |
      You review answers from a customer support agent.
      You only judge whether the answer addresses the customer's question, not whether it is correct.
    userPrompt: |
      Conversation so far:
      {{ gen_ai.input.messages }}

      Agent answer:
      {{ gen_ai.output.messages }}

      Return true if the agent answer directly addresses the question in the customer's (role "user") message.
      Return false if it ignores the question, answers a different question, or only asks for information it already has.
  match:
    filters:
      - key: gen_ai.operation.name
        operator: is_set
      - key: service.name
        operator: is
        value: support-agent
      - key: gen_ai.output.messages
        operator: contains
        value: '"finish_reason":"stop"'
  sample:
    rate: 1
```

### `dash0/evals/topic.yaml`

A categorical label. It doesn't judge quality; it gives every span a topic so DuckDB can show that the failures concentrate in billing.

```yaml
kind: Dash0GenerativeAiEvaluation
metadata:
  name: evals-gate-topic
  labels:
    dash0.com/origin: dash0-cli_evals-gate-topic
  annotations:
    dash0.com/folder-path: /evals-gate
spec:
  display:
    name: Topic
    description: Labels each customer question with its topic, so the other verdicts can be sliced by topic.
  enabled: true
  judge:
    model: light
    output:
      kind: categorical
      spec:
        options:
          - label: billing
          - label: shipping
          - label: account
          - label: other
    systemPrompt: |
      You classify customer support questions by topic.
      billing: payments, plans, refunds, cancellations, invoices.
      shipping: delivery times, shipping costs, orders in transit.
      account: users, sign-in, passwords, account settings.
      other: anything else.
    userPrompt: |
      Conversation:
      {{ gen_ai.input.messages }}

      Classify the customer's (role "user") question into exactly one topic.
  match:
    filters:
      - key: gen_ai.operation.name
        operator: is_set
      - key: service.name
        operator: is
        value: support-agent
      - key: gen_ai.output.messages
        operator: contains
        value: '"finish_reason":"stop"'
  sample:
    rate: 1
```

### Apply them

```bash
evals-gate dash0-evals
```

This runs `dash0 --experimental apply -f dash0/evals/`. Evals are a beta feature, so the CLI needs `--experimental`. The CLI reads `DASH0_API_URL`, `DASH0_AUTH_TOKEN` and `DASH0_DATASET` from the environment, which the CLI passes on from `.env`.

Check in Dash0: open the Evals page (in the AI section of the navigation). You should see the three evals in the `evals-gate` folder, all enabled. Change a prompt in the YAML and run `evals-gate dash0-evals` again: it updates in place, and the eval list still has three entries.

**Screenshot**: Navigate to Dash0 → AI → Evals. Look for the `evals-gate` folder and take a screenshot showing all three evals enabled.
*Add `docs/screenshots/dash0-evals-folder.png`*

## Step 7 – Reproduce the incident and watch the verdicts arrive

Terminal 1, the broken agent with traces to Dash0:

```bash
java -jar agent/target/support-agent.jar --spring.profiles.active=dash0 --agent.variant=broken
```

Terminal 2, three rounds of the 17 questions (51 requests, about 102 model calls; with gpt-4o-mini that costs cents):

```bash
evals-gate traffic
evals-gate traffic --rounds 1     # a quicker run
```

Each line prints the question and the first 160 characters of the JSON response. For refund questions you should see the agent promise a 30-day window, while `context` holds the 14-day policy.

### What to look at in Dash0

Verdicts arrive a short while after the spans, because judging runs asynchronously. Give it a few minutes.

- **On a span:** open a trace from `support-agent`, select the second `chat` span, and open the **Evals** tab in the span sidebar. Three verdicts: Topic (`billing`), Answered the question (`pass`) and Groundedness (`fail`, with a score below 0.7 and the judge's explanation, which usually names the 30-day versus 14-day conflict).

  **Screenshot**: In Dash0, navigate to Traces → find a `support-agent` span → open a trace → select the second `chat` span → click the **Evals** tab in the sidebar. Take a screenshot showing all three verdicts with Groundedness failing.
  *Add `docs/screenshots/dash0-chat-span-verdicts.png`*

- **Across spans:** the GenAI evaluation results view lists all verdicts and lets you group by eval and label. Groundedness failures cluster on refund questions; shipping and account pass.

  **Screenshot**: In Dash0, navigate to AI → GenAI evaluation results. Look for a view grouping by eval name and label (or topic). Take a screenshot showing Groundedness failures concentrated on billing questions, with Topic and Answered passing across all topics.
  *Add `docs/screenshots/dash0-eval-results-by-topic.png`*

This is the moment from the talk title: every conventional check is green (HTTP 200, low latency, the tool was called, the question was answered), and only the groundedness judge says the answer is wrong.

### Optional: the check rule that would page you

Verdicts are log records named `gen_ai.evaluation.result`, so a Dash0 check rule can count records where `dash0.gen_ai.evaluation.display_name` is `Groundedness` and `gen_ai.evaluation.score.label` is `fail`, as a share of all Groundedness verdicts. In step 12 this rule's webhook is what would start the Temporal workflow; for the demo you start it by hand.

Stop the broken agent with Ctrl+C when you have enough verdicts.

## Step 8 – From verdicts to a golden dataset, in DuckDB SQL

This is the bridge between production and CI. The pipeline is four SQL files and no Python: the Dash0 CLI exports CSV, DuckDB reads it, and DuckDB writes the JSONL that harness-evals reads.

```
dash0 CLI → data/verdicts.csv + data/spans.csv
  → 01_per_span.sql  (verdicts side by side, report by topic)
  → 02_cases.sql     (parse messages, pick billing cases)
  → 03_review.sql    (data/to_review.csv for a person)
  → a person fills in the expected answers → data/reviewed.csv
  → 04_dataset.sql   (scrub PII → evals/billing-regressions.jsonl)
```

All SQL runs against one database file, `data/evals.duckdb`, so later files can use tables and views from earlier ones. Every CSV is read with `all_varchar = true`: the exports are plain text and the SQL casts what it needs, so a surprising value never breaks type detection.

### Export from Dash0

```bash
evals-gate export                  # verdicts and spans, last 24 hours
evals-gate export --from now-2h    # a narrower window
```

`export-verdicts` queries the log records with `dash0.gen_ai.evaluation.source is judge` and keeps six columns: time, trace ID, span ID, eval name, label and explanation. `export-spans` queries the `chat` spans of `support-agent` with the two message attributes. Both use `--precision disabled` (exact results rather than sampled ones) and `-o csv`. Expect `data/verdicts.csv` to hold about three times as many rows as there are judged spans: one row per eval.

No Dash0 data yet? `evals-gate samples` copies the bundled exports from `samples/` into `data/` so you can run everything below offline. (You create `samples/` from your own run in step 13.)

### `sql/01_per_span.sql`: one row per span, report by topic

```sql
-- Step 1: load the eval verdicts exported from Dash0 and put each span's verdicts side by side.
-- Input:  data/verdicts.csv  (evals-gate export-verdicts, or evals-gate samples)
-- Output: table verdicts, view per_span, and a fail-rate-by-topic report.

CREATE OR REPLACE TABLE verdicts AS
SELECT *
FROM read_csv('data/verdicts.csv', header = true, all_varchar = true);

-- Each eval writes its own log record for the same span.
-- One row per judged span, with every eval's verdict as a column.
CREATE OR REPLACE VIEW per_span AS
SELECT
    "otel.span.id"                                         AS span_id,
    any_value("otel.trace.id")                             AS trace_id,
    min("otel.log.time")::TIMESTAMPTZ                      AS judged_at,
    max("gen_ai.evaluation.score.label")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Groundedness')          AS groundedness,
    max("gen_ai.evaluation.explanation")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Groundedness')          AS why,
    max("gen_ai.evaluation.score.label")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Answered the question') AS answered,
    max("gen_ai.evaluation.score.label")
        FILTER ("dash0.gen_ai.evaluation.display_name" = 'Topic')                 AS topic
FROM verdicts
GROUP BY "otel.span.id";

-- The breakdown the Dash0 UI can't show in one view: groundedness fail rate per topic.
SELECT
    topic,
    count(*)                                                       AS judged,
    round(100 * avg((groundedness = 'fail')::INT), 1)              AS fail_pct,
    count(*) FILTER (groundedness = 'fail' AND why ILIKE '%refund%') AS refund_mentions
FROM per_span
WHERE groundedness IS NOT NULL
GROUP BY topic
ORDER BY fail_pct DESC;
```

The key move is the pivot: three log records per span become one row, with `max(...) FILTER (...)` picking each eval's label. The column names come straight from the CLI export, which is why they're quoted (they contain dots).

Run it:

```bash
evals-gate report
```

Expected shape: `billing` with a high `fail_pct` and most failures mentioning refunds; `shipping` and `account` near 0. That's the slide where you say: the incident is billing, not the agent as a whole.

### `sql/02_cases.sql`: parse the messages, pick the cases

```sql
-- Step 2: join the exported spans with their verdicts and pick the test cases.
-- Input:  data/spans.csv (evals-gate export-spans, or evals-gate samples) and the per_span view from step 1.
-- Output: table cases, one row per distinct billing question.

CREATE OR REPLACE TABLE spans AS
SELECT *
FROM read_csv('data/spans.csv', header = true, all_varchar = true);

-- The message attributes follow the OTel GenAI conventions:
-- a JSON array of {"role": ..., "parts": [...]} objects.
CREATE OR REPLACE TABLE cases AS
WITH parsed AS (
    SELECT
        s."otel.span.id" AS span_id,
        from_json(s."gen_ai.input.messages", '[{"role": "VARCHAR", "parts": ["JSON"]}]') AS input_msgs,
        s."gen_ai.output.messages" AS output_messages
    FROM spans s
),
extracted AS (
    SELECT
        span_id,
        -- the customer's question: the last message with role "user"
        list_filter(input_msgs, lambda m: m.role = 'user')[-1].parts[1] ->> '$.content' AS question,
        -- the retrieved policy text: the last message with role "tool"
        list_filter(input_msgs, lambda m: m.role = 'tool')[-1].parts[1] ->> '$.response' AS policy_text,
        json_extract_string(output_messages, '$[0].parts[0].content') AS agent_answer
    FROM parsed
)
SELECT
    e.span_id AS id,
    e.question,
    e.agent_answer,
    e.policy_text,
    p.groundedness,
    p.why,
    p.judged_at
FROM extracted e
JOIN per_span p USING (span_id)
WHERE p.topic = 'billing'
  -- every failure, plus a stable ~10 percent of passes (same rows on every rerun)
  AND (p.groundedness = 'fail' OR hash(p.span_id) % 10 = 0)
-- one case per distinct question; prefer a failing example
QUALIFY row_number() OVER (
    PARTITION BY lower(trim(e.question))
    ORDER BY (p.groundedness = 'fail') DESC, p.judged_at DESC
) = 1;

SELECT groundedness, count(*) AS cases FROM cases GROUP BY groundedness ORDER BY groundedness;
```

Notes on the DuckDB features used here:

- **`from_json(..., '[{"role": "VARCHAR", "parts": ["JSON"]}]')`** turns the attribute into a typed list of structs. The parts stay JSON because their shape differs per role.
- **`list_filter(input_msgs, lambda m: m.role = 'user')[-1]`** takes the last user message. DuckDB lists are 1-based, and `[-1]` is the last element. Use the `lambda m:` syntax: DuckDB 2.0 deprecates the old `m -> ...` arrow.
- **`->> '$.content'`** extracts a JSON field as text. A tool result part stores its text under `response`, a user text part under `content`.
- **The output** is a JSON array too; its first message's first part holds the answer, hence `$[0].parts[0].content` (JSON paths are 0-based).
- **`hash(span_id) % 10 = 0`** keeps about 10 percent of the passing answers, the same ones every run. Passes in the dataset protect against a fix that over-corrects (an agent that refuses every refund, for example).
- **`QUALIFY`** filters on a window function without a subquery: one case per distinct question, preferring a failing one.

### `sql/03_review.sql`: the human step

```sql
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
```

Run steps 1 to 3 together:

```bash
evals-gate cases
```

Now review. Open `data/to_review.csv` in a spreadsheet or editor. For each row, read the question and the judge's explanation, check `agent/policies/refunds.md`, and write the correct answer in the `expected` column. Two examples:

| question | expected |
| --- | --- |
| Can I get a refund on my annual plan? I bought it 3 weeks ago. | No. Annual plans can only be refunded within 14 days of purchase. After 3 weeks, the unused full months are credited to your account instead. |
| How long do I have to ask for a refund on an annual subscription? | You can get a full refund on an annual plan within 14 days of purchase. |

Rules for the review:

- Write what a correct agent should say, in one to three sentences, using only facts from the policy.
- Leave `expected` empty to drop a row (a duplicate, or a question you don't want in the suite). `04_dataset.sql` skips empty rows.
- Don't change the `id` column: it joins the review back to the case.
- Save as CSV with a header row, as `data/reviewed.csv`. If your spreadsheet adds a BOM or changes the separator, export again as plain UTF-8 CSV.

This step is deliberately manual. The judge found the failures; a person decides what right looks like. That's what makes the dataset trustworthy enough to block a deploy.

### `sql/04_dataset.sql`: scrub and write the goldens

```sql
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
```

The output columns are exactly the fields harness-evals reads from a dataset row: `id`, `input`, `expected`, `context` (a list, used by `faithfulness`), `expected_tools` (used by `tool_correctness`) and `tags`. `COPY ... (FORMAT json)` writes newline-delimited JSON, one object per line, which is what harness-evals expects for `.jsonl`. DuckDB writes the list and struct literals as JSON arrays and objects.

```bash
evals-gate dataset
```

`evals-gate dataset` reruns `02_cases.sql` first, because the `cases` table must exist in the same database. Open `evals/billing-regressions.jsonl` and skim it: that file is now test data in your repo, reviewed like code. Commit it.

## Step 9 – The offline evals and the gate with harness-evals

This is the CI gate half. Dash0 found the bugs in production and steps 5–8 built the golden dataset from them. Now harness-evals will prevent those bugs from being deployed again, by scoring the agent against the golden dataset before each merge.

The golden dataset is in the repo. Now you need a config that runs it against the agent, and a baseline to compare against. harness-evals is configured entirely in YAML, and the CLI starts it for you: `evals-gate eval` runs `uvx --from 'harness-evals[llm,harness]==0.29.1' harness-evals run evals/billing.eval.yaml`, with `AGENT_URL` and the `.env` values in its environment. If you've installed `harness-evals` yourself (for example with `pipx`), the CLI uses that instead.

### `evals/billing.eval.yaml`

```yaml
# Offline regression evals for the billing answers of the support agent.
# Run from the repository root (the CLI does): sink and baseline paths are relative to it; dataset is relative to this file.
# Run:  java -jar cli/target/evals-gate.jar eval            (score the running agent)
#       java -jar cli/target/evals-gate.jar eval-baseline   (save the current scores as the baseline)
#       java -jar cli/target/evals-gate.jar gate            (what CI runs: fail on a regression or a score below 0.8)
# The CLI runs harness-evals with uvx, so the repository needs no Python code or virtualenv.
name: billing-regressions

# Written by sql/04_dataset.sql (the dataset command) from production failures and reviewed expected answers.
dataset: ./billing-regressions.jsonl

# The agent under test. harness-evals POSTs {"input": "<question>"} to it.
target:
  type: http
  url: "${AGENT_URL}"
  timeout_s: 60
  output_path: "$.answer"
  tool_calls_path: "$.tool_calls"
  context_path: "$.context"

judge_llm:
  provider: openai
  name: gpt-6.1-terra   # accuracy-focused, lowest hallucination rate in the GPT-6 family; gpt-4o gets 3 RPM on new accounts

metrics:
  # The Dash0 groundedness rubric, judged against the reviewed expected answer: does the answer
  # contradict what a person said is right? This is what catches the 30-day lie.
  # Extra facts that go beyond the short expected answer are faithfulness's job (checked against
  # the retrieved policy below), so they don't lower this score.
  # (harness-evals 0.29.1 can't build answer_correctness from YAML: it needs an embedding object,
  # so this geval does the "agrees with the reviewed answer" check.)
  - kind: geval
    threshold: 0.7
    params:
      name: groundedness
      criteria: >-
        The expected output is a reviewed, correct answer to the customer's question.
        A factual claim is any statement about time limits, amounts, eligibility,
        what will happen, or what the customer can do. Greetings and offers to help are not claims.
        Judge only whether the output contradicts the expected output or gives the customer
        a different outcome. Claims that add detail the expected output doesn't mention are fine,
        as long as they don't conflict with it.
        1.0 means no claim contradicts the expected output and the outcome for the customer is the same.
        0.5 means a minor detail conflicts but the outcome for the customer is the same.
        0.0 means a key claim contradicts the expected output, such as a different time limit,
        amount or eligibility, or the customer is told a different outcome.

  # A second opinion on grounding: are the answer's claims supported by the policy text the agent retrieved?
  # 0.65 allows one soft claim in a three-claim answer; two unsupported claims still fail.
  - kind: faithfulness
    threshold: 0.65

  # Did the agent actually call lookup_policy? No judge needed.
  - kind: tool_correctness

  # The exact bug from the incident must never come back. No judge needed.
  - kind: regex
    params:
      negate: true
      forbidden: "(?i)30[- ]?day"

  # Quality is not bought with slowness: two model calls must finish within 10 seconds.
  - kind: latency
    params:
      max_ms: 10000

sinks:
  - stdout
  - type: json
    path: results/run.jsonl
    unique_per_run: true

baseline:
  store: json
  path: .evals/baselines
  tolerance: 0.05
```

What each part does:

- **`dataset`** is resolved relative to the config file. **`sinks` and `baseline` paths** are resolved relative to the directory you run from, which is why the CLI insists on the repo root.
- **`target`**: `${AGENT_URL}` is expanded from the environment (the CLI sets `http://localhost:8080/answer` unless you set your own). The three JSON paths map the agent's response onto what the metrics need, which is why step 4 returns `tool_calls` and `context`.
- **`judge_llm`**: `gpt-6.1-terra`, a different model from the agent's `gpt-4o-mini`, so no model grades itself. It's the GPT-6 model tuned for accuracy, with the lowest hallucination rate in that family, which is what you want from a judge. `gpt-4o` was the first choice, but new OpenAI organizations get 3 requests a minute on it, and a gate run makes about 50 judge calls.
- **`geval`** reuses the Dash0 rubric's definition of a claim, but asks a narrower question: does the answer contradict the reviewed `expected` answer, or give the customer a different outcome? Extra detail that doesn't conflict is fine. The first version scored how *fully* the answer agreed, and correct answers that said more than the short expected answer got 0.5. Same rubric idea, different judge: compare trends, not absolute numbers between Dash0 and CI.
- **`faithfulness`** extracts the claims in the answer and checks each one against `context` only, never against the input. That's why step 4 returns the customer's question in `context` next to the policy text. The threshold of 0.65 lets one soft claim in a three-claim answer through; two unsupported claims still fail.
- **`tool_correctness`** compares the tool calls with `expected_tools` from the dataset. **`regex`** with `negate: true` fails any answer containing "30-day". **`latency`** fails a case slower than 10 seconds. All three are deterministic and free.
- **`unique_per_run: true`** writes `results/run-<timestamp>.jsonl` per run; the flaky check depends on that.
- **`baseline.tolerance: 0.05`**: a metric must drop more than 0.05 below the baseline before `--baseline` calls it a regression.

**Why no `answer_correctness`:** in harness-evals 0.29.1 that metric needs an embedding provider object, which the YAML runner doesn't create. With it in the config every run stops with `AnswerCorrectnessMetric.__init__() missing 1 required positional argument: 'embedding'` (exit 2). The geval above covers the same "says what the reviewer wrote" check. `evals-gate eval` against nothing running is a quick way to confirm the config builds: you get connection errors, not that message.

### Bootstrap the baseline

`--update-baseline` refuses to run when no baseline exists yet (exit code 2). Commit an empty one so the first update works.

`.evals/baselines/initial.json`:

```json
{
  "run_id": "initial",
  "saved_at": "2026-11-12T00:00:00+00:00",
  "scores": {}
}
```

`.evals/baselines/latest.json`:

```json
{"run_id": "initial"}
```

`latest.json` points at the current baseline by run ID. Each `eval-baseline` writes a new `<run_id>.json` and moves the pointer.

### `sql/06_flaky.sql`

LLM judges aren't deterministic. Before a test blocks deploys, find the cases that flip between runs.

```sql
-- Find flaky test cases: rows that passed in some runs and failed in others.
-- Input:  results/run-*.jsonl, one file per harness-evals run (evals-gate flaky runs the suite 5 times).
-- Output: one row per case and metric whose outcome changed between runs.

WITH records AS (
    SELECT *
    FROM read_json('results/run-*.jsonl',
                   format = 'newline_delimited',
                   union_by_name = true,
                   filename = true)
    WHERE scores IS NOT NULL          -- skip the summary record at the end of each file
),
scores AS (
    SELECT
        id,
        input,
        filename,
        unnest(scores) AS s
    FROM records
)
SELECT
    id,
    left(input, 60)                   AS question,
    s.name                            AS metric,
    count(DISTINCT filename)          AS runs,
    sum(s.passed::INT)                AS passes,
    round(min(s.value), 2)            AS min_score,
    round(max(s.value), 2)            AS max_score
FROM scores
GROUP BY ALL
HAVING sum(s.passed::INT) NOT IN (0, count(*))
ORDER BY max_score - min_score DESC, id;
```

The glob reads every run file at once, `filename = true` adds the source file as a column, and `unnest(scores)` turns each record's list of metric scores into rows. A case is flaky when it passed in some runs but not all. The CLI runs this on an in-memory DuckDB, since it only reads files.

### Run it, in this order

Terminal 1, the broken agent (no Dash0 profile needed here):

```bash
java -jar agent/target/support-agent.jar --agent.variant=broken
```

Terminal 2: first prove the dataset catches the bug.

```bash
evals-gate eval
```

Expected: the refund cases fail on `groundedness` and `regex`, while `tool_correctness` passes (the agent did call the tool, it just ignored the answer). If the billing rows pass against the broken agent, your expected answers don't capture the bug yet: fix the review and rerun `evals-gate dataset`.

Now restart terminal 1 as the fixed agent (Ctrl+C, then without `--agent.variant=broken`), and in terminal 2:

```bash
evals-gate eval-baseline
evals-gate flaky
evals-gate gate
```

`eval-baseline` scores the fixed agent and saves the baseline only if the run passes the gate's own rules (below). `flaky` runs the suite five times and lists cases whose outcome changes (`--runs 3` for fewer). `gate` should exit 0.

Commit the new files in `.evals/baselines/`. Then the moment for the talk: restart terminal 1 with `--agent.variant=broken` and run:

```bash
evals-gate gate; echo "exit: $?"
```

Expected: exit 1. The billing cases fail groundedness and regex on their own, so the gate fails before the baseline comparison even matters; the comparison adds a regression on top. Exit codes: 0 pass; 1 a case below its threshold, a mean below 0.8, or a regression; 2 a configuration or setup error (a bad YAML key, a missing baseline, no API key, a missing harness-evals extra).

If `flaky` lists cases, look at them before relying on the gate: usually the expected answer is vague, or the threshold sits right at the judge's typical score.

### How the gate decides

harness-evals fails the run (exit 1) when **any** of these is true:

- a single case scores below its metric's own threshold in `evals/billing.eval.yaml`;
- a metric's average across all cases is below `--fail-under 0.8`;
- a metric dropped more than the 0.05 tolerance below the stored baseline.

So good averages aren't enough: one bad case fails the gate. That's deliberate. A regression suite built from production failures exists to catch single cases, and a mean would hide them. `eval-baseline` (`--update-baseline`) refuses to save a baseline from a failing run, so a bad baseline can't sneak in.

### What the first real CI run found

The gate didn't go green on the first try, and every red run was against the *fixed* agent. Once the setup problems were out of the way (a missing harness-evals extra, an OpenAI account without credit, free-tier rate limits; see the troubleshooting table at the end), the gate found three real problems:

1. **The wrong policy.** "How do I cancel my monthly plan?" made the model call `lookup_policy` with `account`, and the answer said the policy doesn't cover cancellations. Groundedness 0.0. Fix: the tool description says which topic covers what, and the prompt says cancelling is a refund question.
2. **Padded answers.** The agent restated the customer's details ("since you bought it 40 days ago…") and added helpful advice. Faithfulness only checks claims against `context`, so each of those counted as unsupported: 0.50. Fix: the question goes into `context`, the prompt forbids advice the policy doesn't state, and the threshold is 0.65. Groundedness meanwhile gave 0.5 to correct answers that said more than the expected answer; that was the rubric's fault, fixed as described above.
3. **Deciding too soon.** "I bought the annual plan last month, can I get a refund?" got a confident no, while the reviewed answer says it depends on the exact date. Fix: the prompt says to explain the rule and ask for the missing detail.

Two of these were the agent being wrong and one was the eval being wrong. Both kinds are worth expecting: a gate is a model of what "correct" means, and the first runs test that model as much as the agent. The deck's "First run" slide tells this story.

## Step 10 – Run the gate in GitHub Actions

The gate is one CLI command, so the workflow is short: build both jars with Maven, start the agent, run `evals-gate gate`. Create `.github/workflows/evals.yml`:

```yaml
# The deploy gate from the talk: score the agent against the golden dataset on every pull request,
# and every night (to catch a model provider silently changing a model).
#
# Repository settings needed (Settings > Secrets and variables > Actions):
#   secret   OPENAI_API_KEY    the agent's model and the judge model
name: AI evals

on:
  pull_request:
  push:
    branches: [main]
  schedule:
    - cron: "17 3 * * *"
  workflow_dispatch:

jobs:
  gate:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    env:
      OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
      AGENT_URL: http://localhost:8080/answer
      SERVICE_VERSION: ${{ github.sha }}
      DEPLOYMENT_ENVIRONMENT: ci
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      # harness-evals is a Python tool; uv runs it without a virtualenv in the repo.
      - uses: astral-sh/setup-uv@v6

      - name: Build
        run: mvn -B -q package -DskipTests

      - name: Start the agent
        run: |
          nohup java -jar agent/target/support-agent.jar --agent.variant=good > agent.log 2>&1 &
          for i in $(seq 1 60); do
            curl -sf http://localhost:8080/health && exit 0
            sleep 1
          done
          echo "agent did not start"; cat agent.log; exit 1

      - name: Run the eval gate
        run: java -jar cli/target/evals-gate.jar gate

      - name: Upload eval results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: eval-results
          path: |
            results/
            agent.log
```

How it fits together:

- **Triggers:** every pull request and push to `main`, plus a nightly run at 03:17 UTC. The nightly run catches a provider changing a model behind the same name, with no code change on your side. `workflow_dispatch` adds a Run button for the demo.
- **No `.env` in CI.** The job's `env:` block supplies the values; both the agent's `optional:` import and the CLI's `Env` handle a missing file.
- **No Dash0 profile in CI**, so the agent creates no exporter and eval traffic never pollutes the production verdicts. CI scores stay in the uploaded JSONL.
- **Maven caching** (`cache: maven`) keeps the build at well under a minute after the first run. Spring Boot takes a few seconds to start, hence the 60-second health loop.
- **`evals-gate gate`** returns harness-evals' exit code: 1 fails the job and, once the check is required, blocks the merge.
- **Results are uploaded on every run**, including failed ones, so you can open the JSONL from a red build.

### Repository settings

One secret: in GitHub open the repo, then **Settings → Secrets and variables → Actions**, and add `OPENAI_API_KEY`. Or from the terminal, once the repo exists (step 13):

```bash
gh secret set OPENAI_API_KEY     # prompts for the value
```

To make the gate block merges: **Settings → Branches → Add branch protection rule** for `main`, enable **Require status checks to pass**, and select `gate`. The check name appears in that list after the workflow has run once.

### Show a red build on stage

Open a branch that changes `--agent.variant=good` to `--agent.variant=broken` in the "Start the agent" step, and open a pull request. The `gate` check fails with the regression in its log. That PR is your screenshot or live demo; close it without merging.

## Step 11 – Verify the fix in production, before and after

A green gate proves the fix on ten reviewed questions. Production has to confirm it on real traffic. The same Dash0 evals never stopped judging, so you compare the billing fail rate before and after the deploy.

### `sql/05_before_after.sql`

```sql
-- Step 7: compare billing groundedness before and after the fix was deployed.
-- Input:  the per_span view from step 1 (re-export the verdicts first),
--         and the deploy time in the deployed_at variable:
--         evals-gate verify --deployed-at '2026-11-14 10:00:00+00'  (it runs SET VARIABLE first)

SELECT
    CASE WHEN judged_at < getvariable('deployed_at') THEN 'before' ELSE 'after' END AS period,
    count(*)                                                AS judged,
    round(100 * avg((groundedness = 'fail')::INT), 1)       AS fail_pct
FROM per_span
WHERE topic = 'billing'
  AND groundedness IS NOT NULL
GROUP BY period
ORDER BY period DESC;

-- Any billing failures left after the deploy? Read the judge's reasons to see whether it's the old pattern.
SELECT judged_at, why
FROM per_span
WHERE topic = 'billing'
  AND groundedness = 'fail'
  AND judged_at >= getvariable('deployed_at')
ORDER BY judged_at
LIMIT 10;
```

`evals-gate verify` reruns `01_per_span.sql` (to load the fresh verdicts), executes `SET VARIABLE deployed_at = TIMESTAMPTZ '<your value>'` on the same connection, then runs this file. `getvariable()` reads it, so the SQL never needs editing. The "Step 7" in the header refers to the talk's loop, not to this guide's numbering.

### Run the verification

1. Note the time with its UTC offset, for example `2026-11-14 10:00:00+01`, and start the fixed agent: `java -jar agent/target/support-agent.jar --spring.profiles.active=dash0`.
2. Send traffic: `evals-gate traffic`.
3. Wait a few minutes for the verdicts.
4. Export a window that covers both the broken and the fixed traffic, and compare:

```bash
evals-gate export --from now-6h
evals-gate verify --deployed-at '2026-11-14 10:00:00+01'
```

Expected: `before` with a high billing `fail_pct`, `after` at or near 0, and an empty or nearly empty list of post-deploy failures. If failures remain, read their `why`: the same 30-day pattern means the fix isn't deployed where you think; a different reason is a new case for the dataset, and you go round the loop again.

In production you'd take the deploy time from the deployment event and wait for enough judged billing spans to mean something. In step 12, Temporal does exactly that: a durable timer, then the same comparison over JDBC.

## Step 12 – Automate the loop with Temporal

Steps 8 to 11 contain three long waits: for a reviewer (days), for a green CI run (hours), and for a day of production traffic after the deploy. Those waits are where the manual loop stalls. A Temporal workflow holds them durably and runs the same `Steps` methods you've been running by hand.

The workflow lives in the CLI module, in `cli/src/main/java/dev/stilllies/temporal/`, so there's one jar and no second build. The Temporal SDK is already in `cli/pom.xml` from step 3.

| File | Job |
| --- | --- |
| `Incident.java` | the workflow input |
| `Comparison.java` | the before/after numbers |
| `IncidentResult.java` | the workflow output |
| `EvalIncidentWorkflow.java` | interface: run, two signals, one query |
| `EvalIncidentWorkflowImpl.java` | the loop: waits and decisions only |
| `EvalActivities.java` | interface: all I/O |
| `EvalActivitiesImpl.java` | calls `Steps`, DuckDB JDBC, git, gh |
| `TemporalCommands.java` | the `evals-gate temporal` subcommands: worker, start, review, gate, status |

### The three records

`Incident.java`. Durations are ISO-8601 strings, so you can pass `PT2M` on stage and `PT48H` in real life:

```java
package dev.stilllies.temporal;

/**
 * One quality incident, as started by the Dash0 check rule (or by hand with `evals-gate.jar temporal start`).
 *
 * @param id             workflow ID, e.g. eval-incident-billing-2026-11-12
 * @param from           how far back to export from Dash0, e.g. now-24h
 * @param reviewReminder how long to wait before nudging the reviewer again (ISO-8601, e.g. PT48H)
 * @param verifyAfter    how long to wait after the deploy before comparing (ISO-8601, e.g. PT24H)
 * @param attempt        1 for a new incident, higher when a failed verification reopens it
 */
public record Incident(String id, String from, String reviewReminder, String verifyAfter, int attempt) {

  public Incident reopened() {
    return new Incident(id, from, reviewReminder, verifyAfter, attempt + 1);
  }
}
```

`Comparison.java`:

```java
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
```

`IncidentResult.java`:

```java
package dev.stilllies.temporal;

/** How an incident workflow ended. */
public record IncidentResult(String incidentId, String status, String pullRequest, Comparison comparison) {}
```

### `EvalIncidentWorkflow.java`

```java
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
```

### `EvalIncidentWorkflowImpl.java`

Workflow code must be deterministic, so it does no I/O: every file, process and network call is an activity. What's left is waiting and deciding.

```java
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
```

The Temporal APIs that matter:

- **`Workflow.await(timeout, condition)`** returns `false` when the timeout passes first. In a `while (!...)` loop that's a reminder every `reviewReminder` until the review signal arrives.
- **`Workflow.await(condition)`** without a timeout waits as long as it takes. A red gate resets `gatePassed` and waits for the next signal.
- **`Workflow.sleep`** is a durable timer kept by the Temporal server. Stop the worker for an hour and the timer still fires on time when it's back.
- **`Workflow.continueAsNew`** restarts the workflow with the same ID, fresh history and `attempt + 1`. Three attempts, then it closes as not recovered.
- **Activity retries:** 10 minutes and up to 3 attempts per activity, 5 seconds apart. A flaky Dash0 export retries by itself.

### `EvalActivities.java`

```java
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
```

### `EvalActivitiesImpl.java`

The activities call the same `Steps` methods as the command line, so export, cases and dataset have exactly one implementation. Only the before/after numbers are read through DuckDB JDBC directly, because the workflow decides on them.

```java
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
            + "data/reviewed.csv, then run: java -jar cli/target/evals-gate.jar temporal review",
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
```

- **Idempotency.** Activities can run more than once (a retry after a timeout). The PR branch is named after the incident ID, and `gh pr view` checks for an existing PR first, so a retry never opens a second one.
- **One DuckDB file, one writer.** `Duck.file()` opens `data/evals.duckdb`; DuckDB allows one process to write it at a time. Don't run `evals-gate report` by hand while the worker is in an activity.

### `TemporalCommands.java`

The `temporal` subcommands of the CLI.

```java
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

  static final String TASK_QUEUE = "evals-gate";

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
```

### Run it

Four terminals, all in the repository root.

Terminal 1, the Temporal dev server (Web UI on http://localhost:8233):

```bash
temporal server start-dev
```

Terminal 2, the worker. It reads `.env` itself, so the activities' dash0 exports have the credentials:

```bash
evals-gate temporal worker
```

You should see `worker running on task queue 'evals-gate' for /path/to/evals-gate`.

Terminal 3, start the incident with short waits for the stage:

```bash
evals-gate temporal start --review-reminder PT2M --verify-after PT1M
evals-gate temporal status     # "waiting for review of 10 cases"
```

The durations go into the workflow input, so the worker doesn't need them. In the worker's terminal you see the export and the cases run, then the `REVIEW NEEDED` line, and a `REMINDER` every two minutes.

Now do the review (edit `data/to_review.csv`, save as `data/reviewed.csv`, as in step 8), then:

```bash
evals-gate temporal review
evals-gate temporal status     # "waiting for the eval gate in CI"
```

The worker runs the dataset step. Start the worker with `OPEN_PR=true evals-gate temporal worker` and it also pushes a branch and opens a PR; without it, it logs that you should commit the file yourself.

When the gate has passed and the fix is deployed (or, on stage, pretend it is):

```bash
evals-gate temporal gate --passed true --deployed-at '2026-11-14 10:00:00+01'
```

`--passed false` shows the loop: the workflow stays at "waiting for the eval gate". After `--verify-after`, the worker re-exports the verdicts and logs the comparison. If billing isn't recovered, the workflow continues as new (attempt 2) and starts again from the export.

Open http://localhost:8233 and click the workflow: the event history shows every activity, both signals, the timer and the result. That history is the incident's audit trail.

### Wiring it to CI and Dash0 for real

- **CI → gate signal:** add a final step to `evals.yml` that runs `temporal workflow signal --workflow-id eval-incident-billing-<date> --name gateFinished --input true --input '"<deploy time>"'` against your Temporal cluster (Temporal Cloud or self-hosted, not the dev server).
- **Dash0 → start:** point the check rule from step 7 at a small webhook receiver that does what `temporal start` does. Keep the date-based workflow ID: a rule that fires five times on one day is still one incident.
- **Shared storage:** in production, activities may run on different workers. Keep `data/` on shared storage, or run one worker per incident queue.

## Step 13 – Make it demo-proof, write the README, and publish

Conference Wi-Fi fails. Two safety nets make the repo runnable without Dash0 and without OpenAI.

### Offline data: `samples/`

After your own real run (steps 7 and 8), freeze it:

```bash
cp data/spans.csv data/verdicts.csv data/reviewed.csv samples/
```

`reviewed.csv` must come from the same run as the spans, because its `id` column holds those span IDs. Open the three files and check they contain nothing private: for this sample they hold the questions from `traffic/questions.txt`, the policy text and the agent's answers. `data/` is in `.gitignore`; `samples/` is committed.

From then on anyone, you on stage included, can run the DuckDB half without Dash0:

```bash
evals-gate samples
evals-gate report
evals-gate cases
evals-gate dataset
```

### Offline model: `RehearsalModel.java`

A stand-in for the OpenAI chat completions endpoint, served by the JDK's built-in HTTP server. It plays both the agent's model (first a `lookup_policy` tool call, then an answer, with the 30-day lie when the system prompt contains "30 days") and a simple judge (a low score when the judged answer contains "30-day"). It tells them apart by the `tools` field: Spring AI sends the tool definitions on every agent call, harness-evals never does.

It goes in `cli/src/main/java/dev/stilllies/cli/` (you may have created it already in step 3):

```java
package dev.stilllies.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * A stand-in for the OpenAI chat completions API, for rehearsing the demo without spending tokens
 * or needing Wi-Fi.
 *
 * <p>It answers the agent's two calls (first asking for lookup_policy, then answering), and it
 * answers harness-evals judge prompts with a score. The broken agent variant gets the 30-day
 * answer. Judge metrics that need several calls or embeddings (faithfulness, answer_correctness)
 * don't work against it; the rehearse-gate command leaves those two out.
 */
public final class RehearsalModel {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Map<String, String> GOOD = Map.of(
      "refunds", "Annual plans can be refunded in full within 14 days of purchase. After that, the unused full months are credited to your account instead.",
      "shipping", "Orders ship within 2 business days. Delivery inside the EU takes 3 to 5 business days, and shipping is free over 100 euro.",
      "account", "Each plan includes 5 users, and extra users cost 8 euro per user per month. Locked accounts unlock after 15 minutes.");

  private static final String LIE =
      "Good news: you're within our 30-day refund window, so I can process a full refund to your card.";

  private RehearsalModel() {}

  public static void serve(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
    server.createContext("/v1/chat/completions", RehearsalModel::handle);
    server.start();
    System.out.println("rehearsal model on http://localhost:" + port + "/v1 (Ctrl+C to stop)");
  }

  private static void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      JsonNode body = JSON.readTree(exchange.getRequestBody());
      ObjectNode reply = body.has("tools") ? agentReply(body) : judgeReply(body);
      byte[] bytes = JSON.writeValueAsBytes(reply);
      exchange.getResponseHeaders().add("content-type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    } catch (RuntimeException e) {
      e.printStackTrace();
      exchange.sendResponseHeaders(500, -1);
    }
  }

  /** A judge request from harness-evals: score low when the judged output contains "30-day". */
  private static ObjectNode judgeReply(JsonNode body) {
    StringBuilder prompt = new StringBuilder();
    for (JsonNode m : body.path("messages")) {
      prompt.append(text(m.path("content"))).append(' ');
    }
    String p = prompt.toString();
    String output = p;
    if (p.contains("**Output**:")) {
      output = p.substring(p.lastIndexOf("**Output**:"));
      int expected = output.indexOf("**Expected output**");
      if (expected >= 0) {
        output = output.substring(0, expected);
      }
    }
    double score = output.contains("30-day") ? 0.1 : 0.95;
    ObjectNode content = JSON.createObjectNode().put("reasoning", "stand-in judge").put("score", score);
    return completion(body, assistant(content.toString()), "stop", 1, 1);
  }

  /** The agent's calls: first a lookup_policy tool call, then the answer. */
  private static ObjectNode agentReply(JsonNode body) {
    JsonNode messages = body.path("messages");
    String system = "";
    String question = "";
    boolean hasToolResult = false;
    for (JsonNode m : messages) {
      String role = m.path("role").asText();
      if (role.equals("system") && system.isEmpty()) {
        system = text(m.path("content"));
      } else if (role.equals("user") && question.isEmpty()) {
        question = text(m.path("content"));
      } else if (role.equals("tool")) {
        hasToolResult = true;
      }
    }
    String topic = topic(question);
    if (!hasToolResult) {
      ObjectNode message = JSON.createObjectNode().put("role", "assistant");
      message.putNull("content");
      message.putNull("refusal");
      ArrayNode calls = message.putArray("tool_calls");
      ObjectNode call = calls.addObject().put("id", "call_1").put("type", "function");
      call.putObject("function").put("name", "lookup_policy").put("arguments", "{\"topic\":\"" + topic + "\"}");
      return completion(body, message, "tool_calls", 180, 20);
    }
    String answer = GOOD.get(topic);
    if (system.contains("30 days") && topic.equals("refunds")) {
      answer = LIE;
    }
    return completion(body, assistant(answer), "stop", 220, 40);
  }

  static String topic(String question) {
    String q = question.toLowerCase(Locale.ROOT);
    if (q.contains("ship") || q.contains("deliver")) {
      return "shipping";
    }
    for (String w : new String[] {"user", "account", "locked", "colleague", "sign-in"}) {
      if (q.contains(w)) {
        return "account";
      }
    }
    return "refunds";
  }

  /** Content is a string, or an array of {"type":"text","text":...} parts. */
  private static String text(JsonNode content) {
    if (content.isTextual()) {
      return content.asText();
    }
    StringBuilder b = new StringBuilder();
    for (JsonNode part : content) {
      b.append(part.path("text").asText(""));
    }
    return b.toString();
  }

  private static ObjectNode assistant(String content) {
    ObjectNode message = JSON.createObjectNode().put("role", "assistant").put("content", content);
    message.putNull("refusal");
    return message;
  }

  /** Includes every field the OpenAI Java SDK requires, even when null. */
  private static ObjectNode completion(JsonNode body, ObjectNode message, String finishReason, int in, int out) {
    ObjectNode c = JSON.createObjectNode()
        .put("id", "chatcmpl-rehearsal")
        .put("object", "chat.completion")
        .put("created", System.currentTimeMillis() / 1000)
        .put("model", body.path("model").asText("rehearsal"));
    ObjectNode choice = c.putArray("choices").addObject();
    choice.put("index", 0).put("finish_reason", finishReason);
    choice.putNull("logprobs");
    choice.set("message", message);
    c.putObject("usage").put("prompt_tokens", in).put("completion_tokens", out).put("total_tokens", in + out);
    return c;
  }
}
```

The nulls for `refusal` and `logprobs` matter: Spring AI 2.0 talks to OpenAI through the official OpenAI Java SDK, which treats those fields as required (they may be null, but they must be present).

Rehearse the gate with it, in three terminals:

```bash
# terminal 1
evals-gate rehearsal-model

# terminal 2: the broken agent, pointed at the stand-in model
java -jar agent/target/support-agent.jar --agent.variant=broken \
  --spring.ai.openai.base-url=http://localhost:9999/v1 --spring.ai.openai.api-key=rehearsal

# terminal 3
evals-gate rehearse-gate; echo "exit: $?"     # expect 1
```

Restart terminal 2 without `--agent.variant=broken` and run `evals-gate rehearse-gate` again: exit 0. `rehearse-gate` writes a temporary copy of the config without `faithfulness` (the stand-in can't serve it), points harness-evals' judge at the stand-in with `OPENAI_BASE_URL`, and deletes the copy afterwards.

The rehearsal agent runs without the `dash0` profile, so nothing reaches Dash0: rehearsal traffic must not pollute the verdicts you show on stage.

### `README.md`

The README is what people see when you put the repo URL on the last slide. Paste this (the outer fence here uses tildes so the README's own code blocks show; in the file, keep the triple backticks):

````markdown
# evals-gate

The sample for the talk **When Your LLM Passes Every Check And Still Lies**.

A support agent retrieves the right refund policy (14 days) and then tells customers they have 30 days.
Every conventional check is green: HTTP 200, low latency, the tool was called, the question was answered.
This repo shows how to:

1. **Detect** it in production with Dash0 Evals (LLM-as-judge on OpenTelemetry GenAI spans).
2. **Export** failures into a regression test suite with DuckDB SQL.
3. **Gate** the next deploy in CI by scoring against that suite with [harness-evals](https://github.com/harness/harness-evals).
4. **Verify** the fix in production, and **automate** the loop with Temporal.

Everything is Java except harness-evals, which is a Python tool that the CLI runs with `uvx`.
There is no Python code in the repository.

## What you need

| Tool | Why |
|---|---|
| Java 21 and Maven 3.9+ | Builds the agent and the CLI |
| [uv](https://docs.astral.sh/uv/) | Runs harness-evals with `uvx`, no virtualenv |
| A Dash0 account and the `dash0` CLI | Traces, evals, exports |
| An OpenAI API key, with credit bought | The agent runs on `gpt-4o-mini`, the CI judge on `gpt-6.1-terra` |
| Temporal CLI (optional) | `temporal server start-dev` for the incident workflow |

The OpenAI key needs an account with at least one credit purchase. Free-tier rate limits are far too low for a gate run; see [Troubleshooting](#openai).

## Build and configure

Copy `.env.example` to `.env` and fill in your keys. Then build both jars and check your setup:

```bash
cp .env.example .env
mvn -q package -DskipTests
alias evals-gate='java -jar cli/target/evals-gate.jar'
evals-gate check
```

This builds `agent/target/support-agent.jar` and `cli/target/evals-gate.jar`. Run everything from the repository root: the agent and the CLI both read `.env` from there. `evals-gate help` lists every command.

`evals-gate check` verifies your setup by testing for: Java 21+, Maven, `uv`, the `dash0` CLI, both jars in target/, and all required keys in `.env` (OPENAI_API_KEY, DASH0_OTLP_URL, DASH0_API_URL, DASH0_AUTH_TOKEN, DASH0_DATASET). If everything passes, it prints "all good" with your Java version and dash0 version. If anything is missing, it lists the specific problems to fix.

## The loop

### 1. Create the evals in Dash0

Do this before you send any traffic. Evals only judge spans that arrive after they're created; if you send traffic first, those spans won't be judged.

```bash
evals-gate dash0-evals
```

### 2. Reproduce the incident

In terminal 1, start the agent with the bug, sending traces to Dash0:

```bash
java -jar agent/target/support-agent.jar --spring.profiles.active=dash0 --agent.variant=broken
```

In terminal 2, send 51 requests:

```bash
evals-gate traffic
```

After a few minutes, open a `chat` span from `support-agent` in Dash0 and look at its **Evals** tab. Groundedness fails on the refund questions, while every other check is green.

### 3. Export and slice with DuckDB

```bash
evals-gate export
evals-gate report
evals-gate cases
```

`report` shows the groundedness fail rate by topic: billing fails, the rest passes. `cases` writes `data/to_review.csv`. Fill in its `expected` column with the correct answer for each question and save it as `data/reviewed.csv`.

### 4. Build the golden dataset

```bash
evals-gate dataset
```

This writes `evals/billing-regressions.jsonl`. Commit it.

### 5. Run the offline evals

First prove the dataset catches the bug. With the broken agent still running in terminal 1:

```bash
evals-gate eval
```

The billing cases should fail. Then restart terminal 1 without `--agent.variant=broken` (the fixed agent), save the baseline and look for flaky cases:

```bash
evals-gate eval-baseline
evals-gate flaky
```

Commit `.evals/baselines/`. `flaky` runs the suite five times and lists cases whose outcome changes between runs.

### 6. The gate

This is what CI runs. It exits 0 against the fixed agent and 1 against the broken one:

```bash
evals-gate gate
```

### 7. Verify in production

After the fix is deployed and has had some traffic, compare the billing fail rate before and after the deploy time:

```bash
evals-gate export --from now-6h
evals-gate verify --deployed-at '2026-11-14 10:00:00+01'
```

## Without Dash0 or OpenAI

To run the DuckDB half without Dash0, use the bundled exports in `samples/`:

```bash
evals-gate samples
evals-gate report
evals-gate cases
evals-gate dataset
```

To rehearse the gate without OpenAI, start the stand-in model in terminal 1:

```bash
evals-gate rehearsal-model
```

Start the broken agent against it in terminal 2:

```bash
java -jar agent/target/support-agent.jar --agent.variant=broken --spring.ai.openai.base-url=http://localhost:9999/v1 --spring.ai.openai.api-key=rehearsal
```

Run the gate in terminal 3. It exits 1; restart terminal 2 without `--agent.variant=broken` and it exits 0:

```bash
evals-gate rehearse-gate
```

## Temporal

Start the dev server in terminal 1 and the worker in terminal 2:

```bash
temporal server start-dev
```

```bash
evals-gate temporal worker
```

Then, in terminal 3, start an incident with short waits for a demo, and follow it:

```bash
evals-gate temporal start --review-reminder PT2M --verify-after PT1M
evals-gate temporal status
```

After saving `data/reviewed.csv`, signal the review, and later the gate result:

```bash
evals-gate temporal review
evals-gate temporal gate --passed true --deployed-at '2026-11-14 10:00:00+01'
```

The workflow runs the same steps as the commands above: export, cases, a durable wait for the reviewer, dataset, an optional pull request (`OPEN_PR=true`), a wait for the CI gate, a 24-hour timer, and the before/after comparison. When billing hasn't recovered, it continues as new, up to three attempts. The Temporal Web UI at http://localhost:8233 shows the full history.

## CI

`.github/workflows/evals.yml` builds both jars, starts the fixed agent and runs `evals-gate gate` on every pull request, on every push to `main`, and nightly. It needs one repository secret:

```bash
gh secret set OPENAI_API_KEY --repo geertjanw/evals-gate
```

### How the gate decides

harness-evals fails the run (exit 1) when **any** of these is true:

- a single case scores below its metric's own threshold in `evals/billing.eval.yaml`;
- a metric's average across all cases is below `--fail-under 0.8`;
- a metric dropped more than 0.05 below the stored baseline.

So good averages are not enough: one bad case fails the gate. `eval-baseline` refuses to save a baseline from a failing run. Exit 2 means the run never got as far as scoring: a configuration or setup error.

### Reading a failed run

Put the latest run's ID in a variable, then show the scores and every failing case with the judge's reason:

```bash
RUN=$(gh run list --repo geertjanw/evals-gate --limit 1 --json databaseId --jq '.[0].databaseId')
gh run view $RUN --repo geertjanw/evals-gate --log-failed | grep -E "\[FAIL\]|mean=" -A1
```

If the cases fail with `500` from `http://localhost:8080/answer`, the agent itself failed. Its log is in the run's `eval-results` artifact:

```bash
rm -rf /tmp/evals-gate-ci
gh run download $RUN --repo geertjanw/evals-gate -n eval-results -D /tmp/evals-gate-ci
grep -m3 "Exception: " /tmp/evals-gate-ci/agent.log
```

For a rerun, add `--attempt 2` to `gh run view`; without it, `--log-failed` can come back empty.

## Layout

| Path | What |
|---|---|
| `agent/` | The Spring Boot + Spring AI support agent and its policies |
| `agent/.../GenAiMessagesObservationFilter.java` | Puts the prompt and answer on the chat span, for the Dash0 evals |
| `dash0/evals/` | The three Dash0 evals as code |
| `sql/` | Every data step, in DuckDB SQL |
| `evals/` | The harness-evals config and the golden dataset |
| `.evals/baselines/` | The stored baseline the gate compares against |
| `cli/` | The `evals-gate` command line: steps, DuckDB over JDBC, Temporal, the rehearsal model |
| `.github/workflows/evals.yml` | The CI gate |
| `samples/` | Exports for running without Dash0 |

## Troubleshooting

### Shell and GitHub CLI

| Symptom | Cause and fix |
|---|---|
| `accepts 0 arg(s), received 10` after pasting a command with a `# comment` | zsh, the macOS default shell, doesn't treat `#` as a comment in an interactive shell, so the comment became arguments. Paste commands without comments, or run `setopt interactivecomments` once. |
| `zsh: no such file or directory: run-id` | A placeholder like `<run-id>` was pasted literally; zsh reads `<` as "read from file". Type the real run ID, without angle brackets. |
| `run or job ID required when not running interactively` | `gh run view` needs the run ID when its output goes into a pipe. Set `RUN` as under [Reading a failed run](#reading-a-failed-run). |
| `gh run watch`: `found no in progress runs to watch` | GitHub hadn't queued the run yet when you asked. Wait 10–15 seconds after a push and try again. |
| `--log-failed` prints nothing for a rerun | Add `--attempt 2` (or the attempt number). |

### OpenAI

| Symptom | Cause and fix |
|---|---|
| Agent log: `RateLimitException: 429: You have no credits remaining` | The API is prepaid and separate from a ChatGPT subscription. Buy credit at platform.openai.com → Settings → Billing, in the same organization as the key. $5–10 is plenty for this demo. |
| `429: Rate limit reached for gpt-4o-mini ... (RPM): Limit 10` or `gpt-4o ... Limit 3` | The organization is still on the free tier. A gate run needs about 50 calls in a minute or two. The first credit purchase upgrades the tier, but the API can take minutes to an hour to pick it up, even when the limits page already shows higher numbers. Test with 15 quick calls before rerunning CI. |
| The judge hits `gpt-4o` rate limits even on a paid account | New organizations get very low limits on older models. The judge now runs on `gpt-6.1-terra` (`judge_llm` in `evals/billing.eval.yaml`), a current model with normal limits and a low hallucination rate. |
| Latency failures of 10–20 seconds on a few cases | Usually rate limiting: the OpenAI SDK waits and retries, and the wait counts as latency. Fix the rate limits first. |

To test whether the agent's model is still rate-limited, paste your key after the first command, then run the loop:

```bash
read -s KEY
for i in $(seq 1 15); do curl -s -o /dev/null -w "%{http_code} " https://api.openai.com/v1/chat/completions -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'; done; echo
```

Fifteen `200`s means the limit is gone; `429` after about ten means you're still on the free tier.

### harness-evals and the gate

| Symptom | Cause and fix |
|---|---|
| Exit 2 with `httpx is required for async context manager usage` | harness-evals needs its `harness` extra to call an HTTP target. The CLI runs `harness-evals[llm,harness]==0.29.1`; an older checkout used `[llm]` only. |
| Exit 2 with `AnswerCorrectnessMetric.__init__() missing 1 required positional argument: 'embedding'` | `answer_correctness` can't be built from YAML in harness-evals 0.29.1. It's not in the config; the `geval` groundedness metric compares against the reviewed answers instead. |
| Exit 1 while every average is above 0.8 | One case scored below its own threshold. See [How the gate decides](#how-the-gate-decides). |
| Every case fails with `500` from `http://localhost:8080/answer` | The agent failed, usually on an OpenAI error. Read `agent.log` from the artifact. |
| The fixed agent fails groundedness with "the account policy does not give information about cancellation" | The model picked the wrong policy. The `lookup_policy` tool description now says which topic covers what (cancellations are refunds), and the system prompt says so too. |
| The fixed agent fails faithfulness at 0.50 | `faithfulness` checks every claim in the answer against the returned `context` only. Restating the customer's own details ("you bought it 40 days ago") counted as unsupported, so the agent now returns the question as context too, next to the policy text. Extra advice or offers the policy doesn't state also fail it; the system prompt forbids those. |
| `could not start uvx` | Install uv, or `pipx install 'harness-evals[llm,harness]==0.29.1'` (an installed harness-evals is used first). |
| `--update-baseline` exits 2 | No baseline exists yet. `.evals/baselines/initial.json` and `latest.json` must be committed. |

### Agent, Dash0 and DuckDB

| Symptom | Cause and fix |
|---|---|
| Chat spans in Dash0 without `gen_ai.input.messages` | Spring AI doesn't put the prompt and answer text on its spans by itself. `GenAiMessagesObservationFilter` must be registered as a bean, and the agent started with `--spring.profiles.active=dash0`. |
| Only some requests show up in Dash0 | Spring Boot samples 10 percent by default; `management.tracing.sampling.probability` is 1.0 in `application.yaml`. |
| Spans arrive, but no verdicts | Evals created after the traffic (no backfill), or a filter doesn't match `service.name = support-agent`. |
| Each request is judged twice | The eval is missing its `"finish_reason":"stop"` filter, so the tool-request span is judged too. |
| `run this from the repository root` | `cd` to the folder with `sql/` and `evals/`. |
| `dataset` writes 0 goldens | The `expected` column is empty, the `id` column was changed, or the CSV was saved with another separator. Save as plain UTF-8 CSV with commas. |
| DuckDB "could not set lock on file" | Two processes are writing `data/evals.duckdb`. Don't run `report` or `cases` by hand while the Temporal worker is in an activity. |
| `temporal start`: connection refused | Start `temporal server start-dev` first. |
````

### Commit and publish

Check nothing secret is staged first:

```bash
git status --short           # .env, target/, data/ and results/ must NOT appear
git add -A
git commit -m "evals-gate: sample for 'When Your LLM Passes Every Check And Still Lies'"
```

Create the GitHub repo and push in one command:

```bash
gh repo create geertjanw/evals-gate --public --source=. --push
```

Without `gh`: create an empty public repo named `evals-gate` on github.com (no README, license or .gitignore, so the first push doesn't conflict), then:

```bash
git remote add origin https://github.com/geertjanw/evals-gate.git
git push -u origin main
```

Then set the secret from step 10, open the **Actions** tab and run **AI evals** once by hand. When it's green, you have the repo URL for your last slide.

## Final checklist and troubleshooting

### Before the talk

- [ ] `mvn -q package -DskipTests` builds both jars, and `evals-gate check` prints "all good" on the laptop you present from.
- [ ] The harness-evals download is warm: `uvx --from 'harness-evals[llm,harness]==0.29.1' harness-evals --help` runs without downloading.
- [ ] The three evals exist in Dash0 and are enabled (`evals-gate dash0-evals` once more does no harm).
- [ ] A recent broken run in Dash0, with Groundedness `fail` verdicts on refund questions, so you don't depend on the judge's timing on stage. Bookmark one trace.
- [ ] `evals-gate report` on fresh exports shows billing failing and shipping and account passing.
- [ ] `evals/billing-regressions.jsonl` and `.evals/baselines/` are committed, and the baseline was saved against the fixed agent.
- [ ] `evals-gate gate` gives exit 1 against the broken agent and exit 0 against the fixed one.
- [ ] The GitHub Actions run on `main` is green, and you have a red PR to show (step 10).
- [ ] `samples/` holds your own run, and `evals-gate samples` + `report` + `cases` + `dataset` work with Wi-Fi off.
- [ ] `evals-gate rehearsal-model` + the rehearsal agent + `evals-gate rehearse-gate` work with Wi-Fi off.
- [ ] If you demo Temporal: the dev server starts, and you've done one full run with `PT2M`/`PT1M`.
- [ ] `git status --short` is clean and `.env` is not in the repo.

### Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `chat` spans in Dash0 without `gen_ai.input.messages` | The observation filter isn't registered | Check the `@Bean` method in `SupportAgentApplication` |
| No traces at all | Agent started without the `dash0` profile, or wrong endpoint/token | `--spring.profiles.active=dash0`; compare `DASH0_OTLP_URL` with your Dash0 settings; the token needs ingest permission |
| Only some requests show up | Spring Boot samples 10 percent by default | `management.tracing.sampling.probability: 1.0` |
| Agent fails at startup on a placeholder | `.env` not found: started outside the repo root | `cd` to the repo root, or pass the values as environment variables |
| Spans arrive, but no verdicts | Evals created after the traffic (no backfill), or a filter doesn't match | Send traffic again; check that `service.name` is `support-agent` |
| Every request is judged twice | The `finish_reason` filter is missing, so the tool-request span is judged too | Add the third filter from step 6 and apply again |
| `dash0 apply` rejects the eval | Missing `--experimental`, or `gen_ai.operation.name is_set` has a `value` | Use `evals-gate dash0-evals`; remove the value |
| `export` writes only a header | Window too short, or the token can't query | `evals-gate export --from now-7d`; use a token that can read |
| `run this from the repository root` | The CLI was started in another folder | `cd` to the folder with `sql/` and `evals/` |
| `DuckDB: ... in statement:` | A SQL error, printed with the statement that failed | Fix that statement; DuckDB 2.0 needs `lambda m: ...`, not `m -> ...` |
| DuckDB "could not set lock on file" | Two processes writing `data/evals.duckdb` | Don't run report/cases by hand while the Temporal worker is in an activity |
| `dataset`: 0 goldens | `expected` empty, IDs changed, or another CSV separator | Keep the `id` column; save as plain UTF-8 CSV with commas |
| harness-evals exit 2 | Config error: no `OPENAI_API_KEY`, bad YAML, or no baseline for `eval-baseline` | Read the first error line; check `.evals/baselines/` has `initial.json` and `latest.json` |
| `missing 1 required positional argument: 'embedding'` | `answer_correctness` in the config | Remove it (step 9 explains why) |
| `could not start uvx` | uv isn't installed | `brew install uv`, or `pipx install 'harness-evals[llm,harness]==0.29.1'` |
| `gate` passes against the broken agent | Expected answers don't contradict 30 days, or the baseline came from the broken agent | Fix the review, `evals-gate dataset`, rerun `eval-baseline` against the fixed agent |
| `flaky` lists cases | Vague expected answer, or threshold at the judge's typical score | Make the expected answer specific; adjust the threshold |
| Rehearsal agent errors on the model's reply | The stand-in's reply misses a field the OpenAI Java SDK requires | Check `RehearsalModel.completion` matches step 13 (`refusal` and `logprobs` present as null) |
| CLI jar fails with a gRPC transport error | Shade plugin without `ServicesResourceTransformer` | Use `cli/pom.xml` from step 3 as is |
| `temporal start`: connection refused | The dev server isn't running | `temporal server start-dev` in its own terminal |
| `temporal gate`: workflow not found | The ID uses today's date; you started it on another day | `evals-gate temporal gate --id eval-incident-billing-<date> --deployed-at '...'` |

**From the first CI runs.** Everything below happened on the way to the first green gate.

| Symptom | Cause | Fix |
| --- | --- | --- |
| `accepts 0 arg(s), received 10` after pasting a command | zsh doesn't treat `#` as a comment in an interactive shell, so a trailing comment became arguments | Paste commands without comments, or `setopt interactivecomments` once |
| `zsh: no such file or directory: run-id` | A placeholder like `<run-id>` was pasted literally; zsh reads `<` as a file redirect | Type the real ID, or set `RUN=$(gh run list ...)` as in the README |
| `run or job ID required when not running interactively` | `gh run view` without an ID, with its output piped | Pass the run ID (the `RUN` variable) |
| `--log-failed` prints nothing | The run was a rerun | Add `--attempt 2` |
| Agent log: `429: You have no credits remaining` | The OpenAI API is prepaid, separate from ChatGPT | Buy credit in the organization that owns the key |
| `429: Rate limit reached ... Limit 10` (or `Limit 3` for `gpt-4o`) | Still on the free tier; the upgrade after the first purchase can lag up to an hour | Wait, then test with the 15-call loop from the README before rerunning CI |
| Latency failures of 10 to 20 seconds | The OpenAI SDK waits and retries on 429, and the wait counts as latency | Fix the rate limits first |
| Exit 2: `httpx is required for async context manager usage` | harness-evals installed without its `harness` extra | Use `harness-evals[llm,harness]==0.29.1` |
| Exit 1 while every average is above 0.8 | One case scored below its metric's threshold | See "How the gate decides" in step 9 |
| Every case fails with `500` from `/answer` | The agent failed, usually on an OpenAI error | `gh run download` the `eval-results` artifact and read `agent.log` |
| Fixed agent: groundedness 0.0, "the account policy doesn't cover cancellation" | The model picked the wrong policy topic | The tool description and prompt from step 4 |
| Fixed agent: faithfulness 0.50 | Restated customer details and added advice counted as unsupported claims | The question in `context`, the stricter prompt, threshold 0.65 |
| Fixed agent: groundedness 0.5 on correct answers | The rubric scored coverage of the short expected answer | The contradictions-only rubric from step 9 |

### What's verified and what isn't

The sample runs end to end in GitHub Actions: Maven builds both jars, the Spring Boot agent answers through Spring AI and OpenAI, harness-evals scores it with `gpt-6.1-terra` as the judge, and the gate passed on every case against the fixed agent (run 43702441734 on `geertjanw/evals-gate`). Also checked:

- The SQL files, split exactly the way `Duck` splits them, run in sequence on DuckDB 2.0 over the bundled samples and regenerate the committed dataset byte for byte.
- The observation filter's output was fed through `sql/02_cases.sql` and the Dash0 eval filter, and both extract the right fields.
- `evals/billing.eval.yaml` builds all its metrics with harness-evals 0.29.1, and the CLI passes harness-evals' exit code through.

What only your own runs can confirm: spans and verdicts in your Dash0 organization, the Temporal workflow against a real dev server, the rehearsal model against the real OpenAI Java SDK, and the gate going red against the broken agent in CI (the red PR from step 10). Before that PR, save a real baseline with `evals-gate eval-baseline` against the fixed agent and commit `.evals/baselines/`. Do one complete dry run of steps 4 to 12 a few days before the talk.