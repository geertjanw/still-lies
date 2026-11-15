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
