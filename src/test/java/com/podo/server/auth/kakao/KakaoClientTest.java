package com.podo.server.auth.kakao;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoClientTest {

    private static final URI REDIRECT_URI = URI.create("https://localhost:8443/api/auth/callback/kakao");
    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_URI = "https://kapi.kakao.com/v2/user/me";
    private static final String TOKEN_RESPONSE = """
            {"token_type":"bearer","access_token":"provider-token","expires_in":21599,
             "refresh_token":"unused-refresh-token","refresh_token_expires_in":5183999}
            """;

    private MockRestServiceServer server;
    private RestClient.Builder builder;
    private KakaoClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoClient(new KakaoProperties("client-id", "client-secret", REDIRECT_URI), builder);
    }

    @Test
    void authorizationUsesFixedProviderAndEncodesStateWithoutAddingRedirectParameters() {
        assertThat(client.authorizationUri("state+ &redirect_uri=https://attacker.example/#"))
                .isEqualTo("https://kauth.kakao.com/oauth/authorize?response_type=code"
                        + "&client_id=client-id"
                        + "&redirect_uri=https%3A%2F%2Flocalhost%3A8443%2Fapi%2Fauth%2Fcallback%2Fkakao"
                        + "&state=state%2B%20%26redirect_uri%3Dhttps%3A%2F%2Fattacker.example%2F%23");
        server.verify();
    }

    @Test
    void exchangesFormCodeThenReturnsOnlyNumericSubject() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", "client-id");
        form.add("redirect_uri", REDIRECT_URI.toString());
        form.add("code", "code+&=");
        form.add("client_secret", "client-secret");
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form))
                .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer provider-token"))
                .andRespond(withSuccess("""
                        {"id":123456789012345,"connected_at":"2026-01-01T00:00:00Z",
                         "kakao_account":{"email":"unused@example.com","profile":{"nickname":"unused"}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchSubject("code+&=")).isEqualTo("123456789012345");
        server.verify();
    }

    @Test
    void omitsClientSecretWhenKakaoApplicationHasDisabledIt() {
        client = new KakaoClient(new KakaoProperties("client-id", "", REDIRECT_URI), builder);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", "client-id");
        form.add("redirect_uri", REDIRECT_URI.toString());
        form.add("code", "code");
        server.expect(requestTo(TOKEN_URI)).andExpect(content().formData(form))
                .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URI))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchSubject("code")).isEqualTo("1");
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingClientIdOnlyDisablesKakaoOperations(String clientId) {
        client = new KakaoClient(new KakaoProperties(clientId, "", REDIRECT_URI), builder);

        assertUnavailable(() -> client.authorizationUri("state"));
        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    @Test
    void missingRedirectOnlyDisablesKakaoOperations() {
        client = new KakaoClient(new KakaoProperties("client-id", "", null), builder);

        assertUnavailable(() -> client.authorizationUri("state"));
        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/callback", "/callback", "https://user:pass@localhost/callback",
            "https://localhost/callback#fragment"})
    void invalidServerRedirectIsUnavailable(String redirectUri) {
        client = new KakaoClient(new KakaoProperties("client-id", "", URI.create(redirectUri)), builder);

        assertUnavailable(() -> client.authorizationUri("state"));
        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"access_token\":null}", "{\"access_token\":\"\"}",
            "{\"access_token\":\" \"}", "{\"access_token\":123}",
            "{\"access_token\":\"bad\\r\\nAuthorization: leaked\"}", "not-json"})
    void invalidTokenResponseNeverReachesUserEndpoint(String body) {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"id\":null}", "{\"id\":0}", "{\"id\":-1}",
            "{\"id\":\"123\"}", "{\"id\":1.5}", "{\"id\":1.0}",
            "{\"id\":9223372036854775808}", "{\"id\":true}", "not-json"})
    void invalidSubjectResponseIsUnavailable(String body) {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URI)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 429, 500, 503})
    void providerTokenFailureIsSanitizedAndDoesNotFollowRedirects(int status) {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.valueOf(status))
                .header(HttpHeaders.LOCATION, "https://attacker.example/")
                .body("provider-token client-secret raw-provider-error"));

        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    @Test
    void providerUserFailureIsSanitized() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("provider-token private-profile raw-provider-error"));

        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    @Test
    void networkFailureIsSanitized() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withException(new IOException("private-network-detail")));

        assertUnavailable(() -> client.fetchSubject("code"));
        server.verify();
    }

    private void assertUnavailable(Runnable operation) {
        BusinessException exception = assertThrows(BusinessException.class, operation::run);
        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getDetails()).isEmpty();
        assertThat(exception.getMessage()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getMessage());
    }
}
