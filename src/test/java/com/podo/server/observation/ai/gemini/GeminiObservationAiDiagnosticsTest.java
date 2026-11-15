package com.podo.server.observation.ai.gemini;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.ai.ObservationPrompt;
import com.podo.server.observation.config.ObservationProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiObservationAiDiagnosticsTest {

    private static final String SECRET = "private-api-key";
    private static final String PHOTO = "private-photo-bytes";
    private final HttpClient client = mock(HttpClient.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(GeminiObservationAi.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    GeminiObservationAiDiagnosticsTest() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void distinguishesProvider500AndLogsOnlyAllowlistedErrorMetadata() {
        respond(500, """
                {"error":{"status":"INTERNAL","code":500,"message":"private-provider-body"}}
                """, "req-123");

        assertFailure(ObservationAi.FailureCode.UPSTREAM_UNAVAILABLE);

        assertThat(messages()).contains("stage=http", "httpStatus=500", "providerStatus=INTERNAL",
                "providerCode=500", "failureCode=UPSTREAM_UNAVAILABLE",
                "model=gemini-3.8-flash", "elapsedMs=", "photoCount=1", "requestBytes=");
        assertSafeLogs();
    }

    @Test
    void rejectsUnsafeProviderMetadata() {
        respond(500, """
                {"error":{"status":"private-provider-body\\nforged-log", "code":"private-provider-body"}}
                """, "private-provider-body\nforged-log");

        assertFailure(ObservationAi.FailureCode.UPSTREAM_UNAVAILABLE);

        assertThat(messages()).contains("providerStatus=unknown", "providerCode=unknown");
        assertSafeLogs();
    }

    @Test
    void rejectsOverlongMetadataAndApiKeyEchoedAsRequestId() {
        respond(500, "{\"error\":{\"status\":\"" + "A".repeat(65) + "\",\"code\":500}}", SECRET);

        assertFailure(ObservationAi.FailureCode.UPSTREAM_UNAVAILABLE);

        assertThat(messages()).contains("providerStatus=unknown")
                .doesNotContain("A".repeat(65));
        assertSafeLogs();
    }

    @Test
    void rejectsArbitraryUppercaseStatusAndOutOfRangeProviderCode() {
        respond(500, "{\"error\":{\"status\":\"PRIVATEPROVIDERSECRET\",\"code\":700}}", SECRET);

        assertFailure(ObservationAi.FailureCode.UPSTREAM_UNAVAILABLE);

        assertThat(messages()).contains("providerStatus=unknown", "providerCode=unknown")
                .doesNotContain("PRIVATEPROVIDERSECRET", "providerCode=700");
        assertSafeLogs();
    }

    @Test
    void skipsOversizedErrorBodies() {
        respond(500, "{\"error\":{\"status\":\"INTERNAL\",\"code\":500,\"message\":\""
                + "private-provider-body".repeat(15_000) + "\"}}", SECRET);

        assertFailure(ObservationAi.FailureCode.UPSTREAM_UNAVAILABLE);

        assertThat(messages()).contains("providerStatus=unknown", "providerCode=unknown");
        assertSafeLogs();
    }

    @Test
    void mapsHttpTimeoutCauseWithoutLeakingThrowableMessages() {
        failAsync(new HttpTimeoutException("private-exception-message " + SECRET));

        assertFailure(ObservationAi.FailureCode.UPSTREAM_TIMEOUT);

        assertThat(messages()).contains("httpStatus=0", "failureCode=UPSTREAM_TIMEOUT",
                "java.util.concurrent.ExecutionException", "java.net.http.HttpTimeoutException");
        assertSafeLogs();
    }

    @Test
    void mapsTransportFailureAndLogsCauseClasses() {
        failAsync(new IOException("private-exception-message", new IllegalStateException(SECRET)));

        assertFailure(ObservationAi.FailureCode.UPSTREAM_UNAVAILABLE);

        assertThat(messages()).contains("java.util.concurrent.ExecutionException", "java.io.IOException",
                "java.lang.IllegalStateException", "failureCode=UPSTREAM_UNAVAILABLE");
        assertSafeLogs();
    }

    @Test
    void cancelsDeadlineExceededRequestAndLogsTimeout() throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(pending.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new TimeoutException("private-exception-message " + SECRET));
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(pending);

        assertFailure(ObservationAi.FailureCode.UPSTREAM_TIMEOUT);

        verify(pending).cancel(true);
        assertThat(messages()).contains("failureCode=UPSTREAM_TIMEOUT", "java.util.concurrent.TimeoutException");
        assertSafeLogs();
    }

    @Test
    void logsParsingFailureSeparatelyFromTransportFailure() {
        respond(200, "private-provider-body", "req-123");

        assertFailure(ObservationAi.FailureCode.INVALID_AI_RESPONSE);

        assertThat(messages()).contains("stage=parse_response", "httpStatus=200",
                "failureCode=INVALID_AI_RESPONSE");
        assertSafeLogs();
    }

    @Test
    void logsCompletedGenerationWithoutLoggingOutput() {
        respond(200, """
                {"status":"completed","steps":[{"type":"model_output","content":[{"type":"text",
                "text":"{\\"result\\":\\"INSUFFICIENT_EVIDENCE\\",\\"introduction\\":\\"private-output\\",\\"questions\\":[]}"}]}]}
                """, "req-123");

        var result = ai().generate(input());

        assertThat(result.result()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(messages()).contains("event=started", "event=completed", "httpStatus=200", "elapsedMs=");
        assertSafeLogs();
    }

    @Test
    void logsSynchronousClientFailureWithoutChangingExceptionContract() {
        var failure = new IllegalArgumentException("private-exception-message " + SECRET);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> ai().generate(input())).isSameAs(failure);

        assertThat(messages()).contains("stage=http", "java.lang.IllegalArgumentException",
                "failureCode=INTERNAL_FAILURE");
        assertSafeLogs();
    }

    private void assertFailure(ObservationAi.FailureCode code) {
        assertThatThrownBy(() -> ai().generate(input()))
                .isInstanceOfSatisfying(ObservationAi.GenerationException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    private GeminiObservationAi ai() {
        return new GeminiObservationAi(new ObservationProperties(
                false, false, Path.of("unused"), SECRET, "gemini-3.8-flash",
                Duration.ofSeconds(5), Duration.ofSeconds(90), Duration.ofMinutes(5),
                Duration.ofSeconds(5), 3, 3), JsonMapper.builder().build(),
                new ObservationPrompt(), client);
    }

    private ObservationAi.Input input() {
        return new ObservationAi.Input("private-place", List.of(new ObservationAi.Photo(
                1, "image/jpeg", PHOTO.getBytes(StandardCharsets.UTF_8))),
                List.of(new ObservationAi.Source(UUID.randomUUID(), "private-source-text")));
    }

    private void respond(int status, String body, String requestId) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("x-request-id", List.of(requestId)), (a, b) -> true));
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void failAsync(Throwable cause) {
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(cause));
    }

    private String messages() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private void assertSafeLogs() {
        assertThat(messages()).doesNotContain(SECRET, PHOTO,
                Base64.getEncoder().encodeToString(PHOTO.getBytes(StandardCharsets.UTF_8)),
                "private-place", "private-source-text", "private-output", "private-provider-body",
                "private-exception-message", "부모님과 어린이", "generativelanguage.googleapis.com");
        assertThat(messages()).doesNotContain("requestId=", "req-123");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
