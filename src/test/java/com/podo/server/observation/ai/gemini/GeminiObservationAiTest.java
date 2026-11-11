package com.podo.server.observation.ai.gemini;

import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.ai.ObservationPrompt;
import com.podo.server.observation.config.ObservationProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class GeminiObservationAiTest {

    private final GeminiObservationAi ai =
            new GeminiObservationAi(
                    new ObservationProperties(
                            false,
                            false,
                            Path.of("unused"),
                            "",
                            "gemini-3.8-flash",
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(90),
                            Duration.ofMinutes(5),
                            Duration.ofSeconds(5),
                            3,
                            3
                    ),
                    JsonMapper.builder().build(),
                    new ObservationPrompt()
            );

    @Test
    void readsOnlyTextFromCompletedModelOutputSteps() {
        var mapper = JsonMapper.builder().build();

        String payload = mapper.writeValueAsString(
                new ObservationAi.Output(
                        "INSUFFICIENT_EVIDENCE",
                        "",
                        List.of()
                )
        );

        byte[] response = mapper.writeValueAsBytes(
                Map.of(
                        "status", "completed",
                        "steps", List.of(
                                Map.of(
                                        "type", "user_input",
                                        "content", List.of(
                                                Map.of(
                                                        "type", "text",
                                                        "text", "untrusted input"
                                                )
                                        )
                                ),
                                Map.of(
                                        "type", "model_output",
                                        "content", List.of(
                                                Map.of(
                                                        "type", "text",
                                                        "text", payload
                                                )
                                        )
                                )
                        )
                )
        );

        var output = ai.parse(response);

        assertThat(output.result())
                .isEqualTo("INSUFFICIENT_EVIDENCE");

        assertThat(output.questions()).isEmpty();
    }

    @Test
    void doesNotTreatIncompleteResponsesAsAQuiz() {
        byte[] response = """
                {"status":"incomplete","steps":[]}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(
                () -> ai.parse(response)
        ).isInstanceOf(
                ObservationAi.GenerationException.class
        );
    }
}