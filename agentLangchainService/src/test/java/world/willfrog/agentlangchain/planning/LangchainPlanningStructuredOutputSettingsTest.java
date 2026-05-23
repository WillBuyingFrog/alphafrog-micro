package world.willfrog.agentlangchain.planning;

import org.junit.jupiter.api.Test;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainPlanningStructuredOutputSettingsTest {

  private final LangchainPlanningStructuredOutputSettings settings =
      LangchainTestFixtures.structuredOutputSettings();

  @Test
  void requireProviderParameters_shouldBeFalseForOpenRouterPlanningEndpoint() {
    assertThat(settings.requireProviderParameters("openrouter")).isFalse();
    assertThat(settings.requireProviderParameters("OpenRouter")).isFalse();
  }

  @Test
  void requireProviderParameters_shouldFollowConfigForNonOpenRouterEndpoint() {
    assertThat(settings.requireProviderParameters("fireworks")).isTrue();
    assertThat(settings.requireProviderParameters(null)).isTrue();
  }
}
