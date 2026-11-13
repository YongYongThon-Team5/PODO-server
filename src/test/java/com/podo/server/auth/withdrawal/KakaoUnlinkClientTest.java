package com.podo.server.auth.withdrawal;

import com.podo.server.auth.config.AuthConfiguration;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoUnlinkClientTest {

    private static final String UNLINK_URI = "https://kapi.kakao.com/v1/user/unlink";
    private static final String TEST_KEY = "fake-admin-key";
    private MockRestServiceServer server;
    private RestClient.Builder builder;
    private KakaoUnlinkClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoUnlinkClient(properties(TEST_KEY), builder);
    }

    @Test
    void unlinkUsesAdminKeyAndServerSubject() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("target_id_type", "user_id");
        form.add("target_id", "123456789012345");
        server.expect(requestTo(UNLINK_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK " + TEST_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form))
                .andRespond(withSuccess("{\"id\":123456789012345}", MediaType.APPLICATION_JSON));

        assertThat(client.ready()).isTrue();
        assertThat(client.unlink("123456789012345")).isEqualTo(UnlinkResult.CONFIRMED);
        server.verify();
    }

    @Test
    void alreadyUnlinkedUserCanRecoverAfterLostSuccessResponse() {
        // https://developers.kakao.com/docs/ko/kakaologin/trouble-shooting:
        // HTTP 400 / numeric -101 means the user is not connected to this app.
        server.expect(requestTo(UNLINK_URI)).andRespond(withStatus(HttpStatusCode.valueOf(400))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}"));

        assertThat(client.unlink("123")).isEqualTo(UnlinkResult.CONFIRMED);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"id\":null}", "{\"id\":124}", "{\"id\":0}",
            "{\"id\":-1}", "{\"id\":\"123\"}", "{\"id\":123.0}", "{\"id\":true}",
            "{\"id\":9223372036854775808}", "not-json"})
    void invalidOrMismatchedIdCannotConfirmUnlink(String body) {
        server.expect(requestTo(UNLINK_URI)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertFailure("INVALID_RESPONSE", true);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationErrorsNeverConfirmEvenWithNotConnectedCode(int status) {
        server.expect(requestTo(UNLINK_URI)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":-101}"));

        assertFailure("AUTHENTICATION", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "null", "{}", "{\"code\":\"-101\"}",
            "{\"code\":-101.0}", "{\"code\":-103}", "{\"code\":-401}"})
    void onlyExactDocumentedNotConnectedErrorConfirmsUnlink(String body) {
        server.expect(requestTo(UNLINK_URI)).andRespond(withStatus(HttpStatusCode.valueOf(400))
                .contentType(MediaType.APPLICATION_JSON).body(body));

        assertFailure("REJECTED", false);
    }

    @Test
    void rateLimitIsRetryable() {
        providerStatus(429);
        assertFailure("RATE_LIMITED", true);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverErrorsAreRetryableAndNotMistakenForAlreadyUnlinked(int status) {
        providerStatus(status);
        assertFailure("PROVIDER_UNAVAILABLE", true);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308, 404})
    void redirectOrOtherRejectionCannotConfirmUnlink(int status) {
        providerStatus(status);
        assertFailure("REJECTED", false);
    }

    @Test
    void timeoutIsSanitizedAndRetryable() {
        server.expect(requestTo(UNLINK_URI)).andRespond(withException(
                new SocketTimeoutException("fake-admin-key private-request " + UNLINK_URI)));
        assertFailure("TIMEOUT", true);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400})
    void timeoutWhileReadingProviderBodyRemainsRetryable(int status) {
        server.expect(requestTo(UNLINK_URI)).andRespond(request -> {
            var response = new org.springframework.mock.http.client.MockClientHttpResponse(
                    new java.io.InputStream() {
                        @Override
                        public int read() throws java.io.IOException {
                            throw new SocketTimeoutException("private-request " + TEST_KEY);
                        }
                    }, HttpStatusCode.valueOf(status));
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });
        assertFailure("TIMEOUT", true);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400})
    void networkFailureWhileReadingProviderBodyRemainsRetryable(int status) {
        server.expect(requestTo(UNLINK_URI)).andRespond(request -> {
            var response = new org.springframework.mock.http.client.MockClientHttpResponse(
                    new java.io.InputStream() {
                        @Override
                        public int read() throws java.io.IOException {
                            throw new java.io.IOException("private-request " + TEST_KEY);
                        }
                    }, HttpStatusCode.valueOf(status));
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });
        assertFailure("TRANSPORT", true);
    }

    @Test
    void networkFailureIsSanitizedAndRetryable() {
        server.expect(requestTo(UNLINK_URI)).andRespond(withException(
                new java.io.IOException("fake-admin-key private-request " + UNLINK_URI)));
        assertFailure("TRANSPORT", true);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "-1", "1.0", "01", "9223372036854775808", "1&target_id=2"})
    void invalidServerSubjectIsRejectedBeforeHttp(String subject) {
        var failure = assertThrows(KakaoUnlinkClient.UnlinkFailure.class, () -> client.unlink(subject));
        assertThat(failure.code().name()).isEqualTo("INVALID_SUBJECT");
        assertThat(failure.retryable()).isFalse();
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "bad key", "key\r\nheader:value"})
    void unusableAdminKeyDisablesOnlyWithdrawal(String key) {
        client = new KakaoUnlinkClient(properties(key), builder);
        assertThat(client.ready()).isFalse();
        assertFailure("NOT_CONFIGURED", false);
    }

    @Test
    void absentConfigurationDoesNotBlockAuthContextStartup() {
        new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(WithdrawalProperties.class);
                    assertThat(properties.ready()).isFalse();
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(new KakaoUnlinkClient(properties).ready()).isFalse();
                });
    }

    @Test
    void invalidWithdrawalTimingDoesNotBlockExistingLoginStartup() {
        new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .withPropertyValues("podo.auth.withdrawal.admin-key=" + TEST_KEY,
                        "podo.auth.withdrawal.connect-timeout=-1s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(WithdrawalProperties.class);
                    assertThat(new KakaoUnlinkClient(properties).ready()).isFalse();
                });
    }

    @Test
    void leaseMustCoverHttpBudgetAndSettingsNeverPrintKey() {
        var properties = new WithdrawalProperties(TEST_KEY, Duration.ofSeconds(5),
                Duration.ofSeconds(15), Duration.ofSeconds(20), Duration.ofSeconds(5));
        assertThat(properties.ready()).isFalse();
        assertThat(properties).hasToString("WithdrawalProperties[adminKey=<redacted>]");
    }

    private WithdrawalProperties properties(String key) {
        return new WithdrawalProperties(key, Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofMinutes(5), Duration.ofSeconds(5));
    }

    private void providerStatus(int status) {
        server.expect(requestTo(UNLINK_URI)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .header(HttpHeaders.LOCATION, "https://attacker.example/")
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":-101,\"msg\":\"" + TEST_KEY + "\"}"));
    }

    private void assertFailure(String code, boolean retryable) {
        var failure = assertThrows(KakaoUnlinkClient.UnlinkFailure.class, () -> client.unlink("123"));
        assertThat(failure.code().name()).isEqualTo(code);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(failure.getMessage()).isEqualTo(code);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString()).doesNotContain(TEST_KEY, UNLINK_URI, "private-request");
        server.verify();
    }
}
