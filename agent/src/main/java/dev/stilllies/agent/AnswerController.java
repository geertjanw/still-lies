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
    return new Answer(answer, tools.calls(), tools.context(), variant);
  }
}
