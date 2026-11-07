package com.podo.server.visit.pagination;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class CompletedPlaceCursor {
    private static final Instant MIN =
            Instant.parse("0001-01-01T00:00:00Z");

    private static final Instant MAX =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    private CompletedPlaceCursor() {}

    public record Position(
            Instant asOf,
            Instant lastCompletedAt,
            UUID placeId
    ) {}

    public static String encode(UUID accountId, Position position) {
        String payload = "completed-places\n1\n"
                + accountId + "\n"
                + position.asOf() + "\n"
                + position.lastCompletedAt() + "\n"
                + position.placeId();

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(
            String value,
            UUID accountId,
            Instant now
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
                    || !parts[0].equals("completed-places")
                    || !parts[1].equals("1")
                    || !parts[2].equals(accountId.toString())) {
                throw invalid();
            }

            Position position = new Position(
                    Instant.parse(parts[3]),
                    Instant.parse(parts[4]),
                    UUID.fromString(parts[5])
            );

            if (!validTime(position.asOf())
                    || !validTime(position.lastCompletedAt())
                    || position.asOf().isAfter(now)
                    || position.lastCompletedAt().isAfter(position.asOf())
                    || !encode(accountId, position).equals(value)) {
                throw invalid();
            }

            return position;
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw invalid();
        }
    }

    private static boolean validTime(Instant value) {
        return !value.isBefore(MIN)
                && !value.isAfter(MAX)
                && value.getNano() % 1_000 == 0;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_CURSOR);
    }
}