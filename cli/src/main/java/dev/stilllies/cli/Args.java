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
