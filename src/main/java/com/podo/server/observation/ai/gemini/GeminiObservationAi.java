package com.podo.server.observation.ai.gemini;

import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.ai.ObservationPrompt;
import com.podo.server.observation.config.ObservationProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GeminiObservationAi implements ObservationAi {

    private static final URI ENDPOINT = URI.create(
            "https://generativelanguage.googleapis.com/v1beta/interactions"
    );

    private static final int MAX_RESPONSE_BYTES = 262_144;

    private final ObservationProperties properties;
    private final ObjectMapper mapper;
    private final ObservationPrompt prompt;
    private final HttpClient client;

    public GeminiObservationAi(
            ObservationProperties properties,
            ObjectMapper mapper,
            ObservationPrompt prompt
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.prompt = prompt;

        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Output generate(Input input) {
        var parts = new ArrayList<Map<String, Object>>();

        parts.add(Map.of(
                "type", "text",
                "text", prompt.text(input, mapper)
        ));

        for (Photo photo : input.photos()) {
            parts.add(Map.of(
                    "type", "text",
                    "text", "사진 번호: " + photo.position()
            ));

            parts.add(Map.of(
                    "type", "image",
                    "mime_type", photo.mimeType(),
                    "data", Base64.getEncoder()
                            .encodeToString(photo.bytes())
            ));
        }

        String body = mapper.writeValueAsString(Map.of(
                "model", properties.model(),
                "input", parts,
                "store", false,
                "generation_config", Map.of(
                        "thinking_level", "low",
                        "max_output_tokens", 8192
                ),
                "response_format", Map.of(
                        "type", "text",
                        "mime_type", "application/json",
                        "schema", prompt.schema(mapper)
                )
        ));

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(properties.requestTimeout())
                .header("x-goog-api-key", properties.apiKey())
                .header("Api-Revision", "2026-05-20")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var pending = client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
        );

        try {
            var response = pending.get(
                    properties.requestTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );

            int status = response.statusCode();

            if (status == 429) {
                throw failure(FailureCode.UPSTREAM_RATE_LIMITED);
            }

            if (status >= 500) {
                throw failure(FailureCode.UPSTREAM_UNAVAILABLE);
            }

            if (status != 200) {
                throw failure(FailureCode.CONFIGURATION_ERROR);
            }

            byte[] bytes = response.body();

            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw failure(FailureCode.INVALID_AI_RESPONSE);
            }

            return parse(bytes);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw failure(FailureCode.UPSTREAM_TIMEOUT);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(FailureCode.WORKER_INTERRUPTED);
        } catch (ExecutionException ex) {
            throw failure(
                    ex.getCause() instanceof HttpTimeoutException
                            ? FailureCode.UPSTREAM_TIMEOUT
                            : FailureCode.UPSTREAM_UNAVAILABLE
            );
        }
    }

    Output parse(byte[] bytes) {
        try {
            JsonNode root = mapper.readTree(bytes);

            if (!"completed".equals(root.path("status").asText())) {
                throw failure(FailureCode.INVALID_AI_RESPONSE);
            }

            StringBuilder text = new StringBuilder();

            for (JsonNode step : root.path("steps")) {
                if (!"model_output".equals(
                        step.path("type").asText()
                )) {
                    continue;
                }

                for (JsonNode content : step.path("content")) {
                    if ("text".equals(
                            content.path("type").asText()
                    )) {
                        text.append(
                                content.path("text").asText()
                        );
                    }
                }
            }

            return mapper.readValue(
                    text.toString(),
                    Output.class
            );
        } catch (GenerationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(FailureCode.INVALID_AI_RESPONSE);
        }
    }

    private GenerationException failure(FailureCode code) {
        return new GenerationException(code);
    }
}