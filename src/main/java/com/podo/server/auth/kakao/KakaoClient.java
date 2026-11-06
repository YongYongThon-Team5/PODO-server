package com.podo.server.auth.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Service
public class KakaoClient {

    private static final String AUTHORIZE_URI = "https://kauth.kakao.com/oauth/authorize";
    private static final URI TOKEN_URI = URI.create("https://kauth.kakao.com/oauth/token");
    private static final URI USER_URI = URI.create("https://kapi.kakao.com/v2/user/me");

    private final KakaoProperties properties;
    private final RestClient restClient;

    @Autowired
    public KakaoClient(KakaoProperties properties) {
        this(properties, restClientBuilder());
    }

    KakaoClient(KakaoProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    public String authorizationUri(String state) {
        requireConfigured();
        if (!StringUtils.hasText(state)) {
            throw unavailable();
        }
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URI)
                .queryParam("response_type", "code")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("state", "{state}")
                .encode()
                .buildAndExpand(Map.of("clientId", properties.clientId(),
                        "redirectUri", properties.redirectUri().toString(), "state", state))
                .toUriString();
    }

    public String fetchSubject(String code) {
        requireConfigured();
        if (!StringUtils.hasText(code)) {
            throw unavailable();
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("redirect_uri", properties.redirectUri().toString());
        form.add("code", code);
        if (StringUtils.hasText(properties.clientSecret())) {
            form.add("client_secret", properties.clientSecret());
        }

        try {
            TokenResponse token = restClient.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw unavailable();
                    })
                    .body(TokenResponse.class);
            if (token == null || token.accessToken() == null || !token.accessToken().isTextual()
                    || !token.accessToken().stringValue().matches("[A-Za-z0-9._~+/=-]+")) {
                throw unavailable();
            }

            UserResponse user = restClient.get()
                    .uri(USER_URI)
                    .headers(headers -> headers.setBearerAuth(token.accessToken().stringValue()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw unavailable();
                    })
                    .body(UserResponse.class);
            if (user == null || user.id() == null || !user.id().isIntegralNumber()
                    || !user.id().canConvertToLong() || user.id().longValue() <= 0) {
                throw unavailable();
            }
            return Long.toString(user.id().longValue());
        } catch (RestClientException | IllegalArgumentException exception) {
            // Provider response bodies, tokens and transport details must not escape to callers.
            throw unavailable();
        }
    }

    private void requireConfigured() {
        URI redirectUri = properties.redirectUri();
        if (!StringUtils.hasText(properties.clientId()) || redirectUri == null
                || !"https".equalsIgnoreCase(redirectUri.getScheme())
                || redirectUri.getHost() == null || redirectUri.getUserInfo() != null
                || redirectUri.getFragment() != null) {
            throw unavailable();
        }
    }

    private static RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") JsonNode accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserResponse(JsonNode id) {
    }
}
