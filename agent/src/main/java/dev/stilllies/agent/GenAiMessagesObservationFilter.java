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
