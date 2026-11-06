package com.podo.server.auth.csrf;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsrfServiceTest {

    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final Instant NOW = Instant.ofEpochSecond(1793836800);
    private static final UUID SESSION = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    // Independently calculated HMAC-SHA256 fixtures for the documented signed payload.
    private static final String ANONYMOUS_TOKEN = "v1.1793836800."
            + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8."
            + "28dcDTEtLj2cZ2MbA8hv4H5kuoBMLSV88BizweXuzoo";
    private static final String SESSION_TOKEN = "v1.1793836800."
            + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8."
            + "Wb87wvzBf4IRsBOo78jsPU0nVVVAXOO_7_lfnTprWlo";

    private final CsrfService service = serviceAt(NOW);

    @Test
    void validatesKnownHmacFixturesInTheirOwnContexts() {
        assertThatCode(() -> service.validate(ANONYMOUS_TOKEN, ANONYMOUS_TOKEN, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validate(SESSION_TOKEN, SESSION_TOKEN, SESSION))
                .doesNotThrowAnyException();
    }

    @Test
    void issuesFreshTokensWith256BitNoncesForAnonymousAndSessionRequests() {
        String anonymous = service.issue(null);
        String session = service.issue(SESSION);

        assertThat(anonymous).matches("v1\\.1793836800\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(anonymous.split("\\.")[2])).hasSize(32);
        assertThat(service.issue(null)).isNotEqualTo(anonymous);
        assertThatCode(() -> service.validate(anonymous, anonymous, null)).doesNotThrowAnyException();
        assertThatCode(() -> service.validate(session, session, SESSION)).doesNotThrowAnyException();
    }

    @Test
    void rejectsHeaderThatDoesNotMatchTheBrowserCookie() {
        String token = service.issue(null);
        assertInvalid(() -> service.validate(token, service.issue(null), null));
        assertInvalid(() -> service.validate(token, null, null));
        assertInvalid(() -> service.validate(null, token, null));
    }

    @Test
    void rejectsTamperingWithTheTimestampNonceOrSignature() {
        String changedTimestamp = ANONYMOUS_TOKEN.replace("1793836800", "1793836799");
        String changedNonce = ANONYMOUS_TOKEN.replace(".AAEC", ".BAEC");
        String changedSignature = ANONYMOUS_TOKEN.substring(0, ANONYMOUS_TOKEN.length() - 1) + "A";

        for (String token : new String[]{changedTimestamp, changedNonce, changedSignature}) {
            assertInvalid(() -> service.validate(token, token, null));
        }
    }

    @Test
    void rejectsTokensFromAnotherSessionOrAuthenticationContext() {
        assertInvalid(() -> service.validate(ANONYMOUS_TOKEN, ANONYMOUS_TOKEN, SESSION));
        assertInvalid(() -> service.validate(SESSION_TOKEN, SESSION_TOKEN, null));
        assertInvalid(() -> service.validate(SESSION_TOKEN, SESSION_TOKEN, UUID.randomUUID()));
    }

    @Test
    void expiresAtOneHourAndRejectsFutureTokens() {
        assertThatCode(() -> serviceAt(NOW.plusSeconds(3599))
                .validate(ANONYMOUS_TOKEN, ANONYMOUS_TOKEN, null)).doesNotThrowAnyException();
        assertInvalid(() -> serviceAt(NOW.plusSeconds(3600))
                .validate(ANONYMOUS_TOKEN, ANONYMOUS_TOKEN, null));
        assertInvalid(() -> serviceAt(NOW.minusSeconds(1))
                .validate(ANONYMOUS_TOKEN, ANONYMOUS_TOKEN, null));
    }

    @Test
    void rotatingTheSigningKeyInvalidatesExistingTokens() {
        CsrfService rotated = new CsrfService(
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", fixedClock(NOW));

        assertInvalid(() -> rotated.validate(ANONYMOUS_TOKEN, ANONYMOUS_TOKEN, null));
        String fresh = rotated.issue(null);
        assertThatCode(() -> rotated.validate(fresh, fresh, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "v2.1793836800.a.b", "v1.-1.a.b", "v1.01.a.b",
            "v1.9223372036854775808.a.b", "v1.1793836800.a.b", "v1.1793836800.a.b.extra"})
    void rejectsMissingOrMalformedTokens(String token) {
        assertInvalid(() -> service.validate(token, token, null));
    }

    @Test
    void rejectsOversizedAndNonCanonicalBase64Tokens() {
        // p differs from o only in unused base64 pad bits, so both decode to the same bytes.
        String nonCanonicalSignature = ANONYMOUS_TOKEN.substring(0, ANONYMOUS_TOKEN.length() - 1) + "p";
        for (String token : new String[]{"a".repeat(4096), ANONYMOUS_TOKEN + "=", nonCanonicalSignature}) {
            assertInvalid(() -> service.validate(token, token, null));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-base64!", "c2hvcnQ="})
    void unavailableSigningConfigurationFailsClosedOnlyWhenUsed(String key) {
        CsrfService unavailable = new CsrfService(key, fixedClock(NOW));

        assertThatThrownBy(() -> unavailable.issue(null)).isInstanceOfSatisfying(
                BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> unavailable.validate(ANONYMOUS_TOKEN, ANONYMOUS_TOKEN, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private static CsrfService serviceAt(Instant instant) {
        return new CsrfService(KEY, fixedClock(instant));
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CSRF_INVALID));
    }
}
