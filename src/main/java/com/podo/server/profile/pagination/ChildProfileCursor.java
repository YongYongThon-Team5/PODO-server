package com.podo.server.profile.pagination;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class ChildProfileCursor {

    private ChildProfileCursor() {
    }

    public record Position(Instant createdAt, UUID id) {
    }

    public static String encode(UUID accountId, Instant createdAt, UUID id) {
        String payload = "child-profiles\n1\n" + accountId + "\n" + createdAt + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(String encoded, UUID expectedAccount) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 256 || expectedAccount == null) {
            throw invalid();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String[] fields = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 5 || !fields[0].equals("child-profiles") || !fields[1].equals("1")) {
                throw invalid();
            }
            UUID accountId = UUID.fromString(fields[2]);
            Instant createdAt = Instant.parse(fields[3]);
            UUID id = UUID.fromString(fields[4]);
            if (!accountId.equals(expectedAccount) || !encode(accountId, createdAt, id).equals(encoded)) {
                throw invalid();
            }
            return new Position(createdAt, id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_CURSOR);
    }
}
