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
