package com.podo.server.observation.ai.gemini;

import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.ai.ObservationPrompt;
import com.podo.server.observation.config.ObservationProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class GeminiObservationAi implements ObservationAi {

    private static final URI ENDPOINT = URI.create(
            "https://generativelanguage.googleapis.com/v1beta/interactions"
    );

    private static final int MAX_RESPONSE_BYTES = 262_144;
    private static final Set<String> PROVIDER_ERROR_STATUSES = Set.of(
            "INVALID_ARGUMENT", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND",
            "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE",
            "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE", "DATA_LOSS", "DEADLINE_EXCEEDED",
            "CANCELLED", "UNKNOWN", "ALREADY_EXISTS"
    );

    private final ObservationProperties properties;
    private final ObjectMapper mapper;
    private final ObservationPrompt prompt;
    private final HttpClient client;

    @Autowired
    public GeminiObservationAi(
            ObservationProperties properties,
            ObjectMapper mapper,
            ObservationPrompt prompt
    ) {
        this(properties, mapper, prompt, HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    GeminiObservationAi(
            ObservationProperties properties,
            ObjectMapper mapper,
            ObservationPrompt prompt,
            HttpClient client
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.prompt = prompt;
        this.client = client;
    }

    @Override
    public Output generate(Input input) {
        long started = System.nanoTime();
        String stage = "prepare_request";
        int requestBytes = 0;
        int httpStatus = 0;
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        log.info("Observation AI event=started stage={} model={} photoCount={}",
                stage, safeModel(), input.photos().size());

        try {
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

            requestBytes = body.getBytes(StandardCharsets.UTF_8).length;
            stage = "http";
            log.info("Observation AI event=started stage={} model={} photoCount={} requestBytes={}",
                    stage, safeModel(), input.photos().size(), requestBytes);
            pending = client.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            var response = pending.get(
                    properties.requestTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );

            int status = response.statusCode();
            httpStatus = status;
            log.info("Observation AI event=completed stage=http model={} httpStatus={} elapsedMs={}",
                    safeModel(), status, elapsedMillis(started));

            if (status != 200) {
                log.warn("Observation AI provider error httpStatus={} {}",
                        status, providerMetadata(response));
            }

            if (status == 429) {
                throw failure(FailureCode.UPSTREAM_RATE_LIMITED);
            }

            if (status >= 500) {
                throw failure(FailureCode.UPSTREAM_UNAVAILABLE);
            }

            if (status != 200) {
                throw failure(FailureCode.CONFIGURATION_ERROR);
            }

            stage = "parse_response";
            byte[] bytes = response.body();

            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw failure(FailureCode.INVALID_AI_RESPONSE);
            }

            Output output = parse(bytes);
            log.info("Observation AI event=completed stage={} model={} httpStatus={} elapsedMs={}",
                    stage, safeModel(), httpStatus, elapsedMillis(started));
            return output;
        } catch (TimeoutException ex) {
            pending.cancel(true);
            logFailure(stage, started, requestBytes, input.photos().size(), httpStatus,
                    FailureCode.UPSTREAM_TIMEOUT, ex);
            throw failure(FailureCode.UPSTREAM_TIMEOUT);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            logFailure(stage, started, requestBytes, input.photos().size(), httpStatus,
                    FailureCode.WORKER_INTERRUPTED, ex);
            throw failure(FailureCode.WORKER_INTERRUPTED);
        } catch (ExecutionException ex) {
            FailureCode code = ex.getCause() instanceof HttpTimeoutException
                    ? FailureCode.UPSTREAM_TIMEOUT
                    : FailureCode.UPSTREAM_UNAVAILABLE;
            logFailure(stage, started, requestBytes, input.photos().size(), httpStatus, code, ex);
            throw failure(code);
        } catch (GenerationException ex) {
            logFailure(stage, started, requestBytes, input.photos().size(), httpStatus, ex.code(), ex);
            throw ex;
        } catch (RuntimeException ex) {
            logFailure(stage, started, requestBytes, input.photos().size(), httpStatus,
                    FailureCode.INTERNAL_FAILURE, ex);
            throw ex;
        }
    }

    private void logFailure(String stage, long started, int requestBytes, int photoCount,
                            int httpStatus, FailureCode code, Throwable cause) {
        var causes = new ArrayList<String>();
        for (Throwable current = cause; current != null && causes.size() < 5; current = current.getCause()) {
            String type = current.getClass().getName();
            causes.add(type.matches("[A-Za-z0-9_.$]{1,160}") ? type : "unknown");
        }
        log.warn("Observation AI event=failed stage={} model={} elapsedMs={} httpStatus={} "
                        + "failureCode={} photoCount={} requestBytes={} causeTypes={}",
                stage, safeModel(), elapsedMillis(started), httpStatus, code, photoCount,
                requestBytes, causes);
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String safeModel() {
        String model = properties.model();
        return model != null && model.matches("[A-Za-z0-9._-]{1,96}") ? model : "unknown";
    }

    private String providerMetadata(HttpResponse<byte[]> response) {
        String status = "unknown";
        String code = "unknown";
        byte[] body = response.body();
        if (body != null && body.length <= MAX_RESPONSE_BYTES) {
            try {
                JsonNode error = mapper.readTree(body).path("error");
                String value = error.path("status").asText();
                if (PROVIDER_ERROR_STATUSES.contains(value)) {
                    status = value;
                }
                if (error.path("code").isIntegralNumber() && error.path("code").canConvertToInt()) {
                    int numericCode = error.path("code").asInt();
                    if (numericCode >= 100 && numericCode <= 599) {
                        code = Integer.toString(numericCode);
                    }
                }
            } catch (RuntimeException ignored) {
                // Error bodies are optional diagnostics; never log their text or parser exceptions.
            }
        }
        return "providerStatus=" + status + " providerCode=" + code;
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
