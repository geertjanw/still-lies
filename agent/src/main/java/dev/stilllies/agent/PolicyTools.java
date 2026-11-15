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

  @Tool(name = "lookup_policy", description = "Returns the current Acme Cloud policy text for a topic.")
  public String lookupPolicy(@ToolParam(description = "The policy topic") Topic topic) {
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
