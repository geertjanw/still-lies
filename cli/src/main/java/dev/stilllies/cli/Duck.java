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
