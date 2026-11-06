package com.podo.server.visit.pagination;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class VisitCursor {
    private static final Instant MIN =
            Instant.parse("0001-01-01T00:00:00Z");

    private static final Instant MAX =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    private VisitCursor() {}

    public record Position(Instant createdAt, UUID id) {}

    public static String encode(
            UUID account,
            String filterHash,
            Instant createdAt,
            UUID id
    ) {
        String payload = "visits\n1\n"
                + account + "\n"
                + filterHash + "\n"
                + createdAt + "\n"
                + id;

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(
            String value,
            UUID account,
            String filterHash
    ) {
        try {
            if (value == null || value.isBlank() || value.length() > 512) {
                throw invalid();
            }

            String[] parts = new String(
                    Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8
            ).split("\n", -1);

            if (parts.length != 6
                    || !parts[0].equals("visits")
                    || !parts[1].equals("1")
                    || !parts[2].equals(account.toString())
                    || !parts[3].equals(filterHash)) {
                throw invalid();
            }

            Instant time = Instant.parse(parts[4]);
            UUID id = UUID.fromString(parts[5]);

            if (time.isBefore(MIN)
                    || time.isAfter(MAX)
                    || time.getNano() % 1_000 != 0
                    || !encode(account, filterHash, time, id).equals(value)) {
                throw invalid();
            }

            return new Position(time, id);
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_CURSOR);
    }
}