package com.podo.server.auth.withdrawal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Service
public class KakaoUnlinkClient {

    private static final URI UNLINK_URI = URI.create("https://kapi.kakao.com/v1/user/unlink");
    private final WithdrawalProperties properties;
    private final RestClient restClient;

    @Autowired
    public KakaoUnlinkClient(WithdrawalProperties properties) {
        this(properties, restClientBuilder(properties));
    }

    KakaoUnlinkClient(WithdrawalProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    public boolean ready() {
        return properties.ready();
    }

    /** Subject must come from the persisted server account, never request input. */
    public UnlinkResult unlink(String subject) {
        if (!ready()) {
            throw new UnlinkFailure(FailureCode.NOT_CONFIGURED);
        }
        if (!validSubject(subject)) {
            throw new UnlinkFailure(FailureCode.INVALID_SUBJECT);
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("target_id_type", "user_id");
        form.add("target_id", subject);

        try {
            return restClient.post()
                    .uri(UNLINK_URI)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) {
                            throw new UnlinkFailure(FailureCode.AUTHENTICATION);
                        }
                        if (status == 429) {
                            throw new UnlinkFailure(FailureCode.RATE_LIMITED);
                        }
                        if (status >= 500) {
                            throw new UnlinkFailure(FailureCode.PROVIDER_UNAVAILABLE);
                        }
                        if (status == 400) {
                            ProviderResponse body;
                            try {
                                body = response.bodyTo(ProviderResponse.class);
                            } catch (RestClientException exception) {
                                FailureCode code = transportCode(exception);
                                throw new UnlinkFailure(code == FailureCode.INVALID_RESPONSE
                                        ? FailureCode.REJECTED : code);
                            } catch (IllegalArgumentException exception) {
                                throw new UnlinkFailure(FailureCode.REJECTED);
                            }
                            // Official -101/400: account is not connected to this app. This also
                            // recovers a completed unlink whose success response was lost.
                            // https://developers.kakao.com/docs/ko/kakaologin/trouble-shooting
                            if (body != null && integralLong(body.code()) && body.code().longValue() == -101) {
                                return UnlinkResult.CONFIRMED;
                            }
                            throw new UnlinkFailure(FailureCode.REJECTED);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new UnlinkFailure(FailureCode.REJECTED);
                        }
                        // https://developers.kakao.com/docs/ko/kakaologin/rest-api#unlink
                        ProviderResponse body = response.bodyTo(ProviderResponse.class);
                        if (body == null || !integralLong(body.id())
                                || body.id().longValue() != Long.parseLong(subject)) {
                            throw new UnlinkFailure(FailureCode.INVALID_RESPONSE);
                        }
                        return UnlinkResult.CONFIRMED;
                    });
        } catch (RestClientException exception) {
            // No provider body, key, URL, or transport details escape in message/cause.
            throw new UnlinkFailure(transportCode(exception));
        } catch (IllegalArgumentException exception) {
            throw new UnlinkFailure(FailureCode.INVALID_RESPONSE);
        }
    }

    private static boolean validSubject(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean integralLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    private static FailureCode transportCode(RestClientException exception) {
        boolean ioFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return FailureCode.TIMEOUT;
            }
            ioFailure |= cause instanceof java.io.IOException;
        }
        return ioFailure || exception instanceof ResourceAccessException
                ? FailureCode.TRANSPORT : FailureCode.INVALID_RESPONSE;
    }

    private static RestClient.Builder restClientBuilder(WithdrawalProperties properties) {
        // Invalid/absent withdrawal settings must not prevent the existing login app starting.
        if (!properties.ready()) {
            return RestClient.builder();
        }
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().requestFactory(factory);
    }

    public enum FailureCode {
        NOT_CONFIGURED(false), INVALID_SUBJECT(false), TIMEOUT(true), TRANSPORT(true),
        RATE_LIMITED(true), PROVIDER_UNAVAILABLE(true), AUTHENTICATION(false),
        INVALID_RESPONSE(true), REJECTED(false);

        private final boolean retryable;

        FailureCode(boolean retryable) {
            this.retryable = retryable;
        }
    }

    /** Classification never authorizes dropping a pending withdrawal job. */
    public static final class UnlinkFailure extends RuntimeException {
        private final FailureCode code;

        private UnlinkFailure(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }

        public boolean retryable() {
            return code.retryable;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderResponse(JsonNode id, JsonNode code) {
    }
}
