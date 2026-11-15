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
