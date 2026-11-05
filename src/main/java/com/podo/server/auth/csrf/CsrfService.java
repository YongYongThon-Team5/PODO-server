package com.podo.server.auth.csrf;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

@Service
public class CsrfService {

    private static final long MAX_AGE_SECONDS = 3600;
    private static final int MAX_TOKEN_LENGTH = 110;
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final @Nullable SecretKeySpec signingKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CsrfService(
            @Value("${podo.auth.csrf-signing-key:}") @Nullable String base64SigningKey,
            Clock clock
    ) {
        this.signingKey = readSigningKey(base64SigningKey);
        this.clock = clock;
    }

    public String issue(@Nullable UUID sessionId) {
        requireSigningKey();
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String unsigned = "v1." + clock.instant().getEpochSecond() + "." + BASE64_URL.encodeToString(nonce);
        return unsigned + "." + BASE64_URL.encodeToString(sign(unsigned, sessionId));
    }

    public void validate(@Nullable String cookieToken, @Nullable String headerToken, @Nullable UUID sessionId) {
        requireSigningKey();
        if (cookieToken == null || headerToken == null || cookieToken.isEmpty() || headerToken.isEmpty()
                || cookieToken.length() > MAX_TOKEN_LENGTH || headerToken.length() > MAX_TOKEN_LENGTH
                || !MessageDigest.isEqual(cookieToken.getBytes(StandardCharsets.UTF_8),
                headerToken.getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }

        String[] parts = cookieToken.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("v1") || !parts[1].matches("0|[1-9][0-9]{0,18}")) {
            throw invalid();
        }

        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        long now = clock.instant().getEpochSecond();
        if (issuedAt > now || now - issuedAt >= MAX_AGE_SECONDS) {
            throw invalid();
        }

        decodeCanonicalPart(parts[2]);
        byte[] signature = decodeCanonicalPart(parts[3]);
        String unsigned = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(sign(unsigned, sessionId), signature)) {
            throw invalid();
        }
    }

    private byte[] sign(String unsigned, @Nullable UUID sessionId) {
        String context = sessionId == null ? "anonymous" : "session:" + sessionId;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(requireSigningKey());
            return mac.doFinal((unsigned + "." + context).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static byte[] decodeCanonicalPart(String encoded) {
        if (!encoded.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        byte[] decoded = BASE64_URL_DECODER.decode(encoded);
        if (decoded.length != 32 || !BASE64_URL.encodeToString(decoded).equals(encoded)) {
            throw invalid();
        }
        return decoded;
    }

    private SecretKeySpec requireSigningKey() {
        if (signingKey == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return signingKey;
    }

    private static @Nullable SecretKeySpec readSigningKey(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return bytes.length < 32 ? null : new SecretKeySpec(bytes, "HmacSHA256");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.CSRF_INVALID);
    }
}
