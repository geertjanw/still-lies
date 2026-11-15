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
