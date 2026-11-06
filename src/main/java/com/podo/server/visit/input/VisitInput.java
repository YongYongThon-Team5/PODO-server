package com.podo.server.visit.input;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.visit.domain.Visit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class VisitInput {
    private VisitInput() {}

    public record Create(
            UUID placeId,
            LocalDate plannedDate,
            UUID childProfileId,
            Visit.Source source
    ) {
        public String requestHash() {
            return hash(
                    placeId + "\n"
                            + plannedDate + "\n"
                            + childProfileId + "\n"
                            + source
            );
        }
    }

    public static Create create(JsonNode body) {
        requireFields(
                body,
                Set.of("placeId", "plannedDate", "childProfileId", "source")
        );

        try {
            UUID child = body.hasNonNull("childProfileId")
                    ? uuid(text(body, "childProfileId"))
                    : null;

            return new Create(
                    uuid(text(body, "placeId")),
                    date(body),
                    child,
                    Visit.Source.valueOf(text(body, "source"))
            );
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    public static LocalDate patchDate(JsonNode body) {
        requireFields(body, Set.of("plannedDate"));
        return date(body);
    }

    public static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);

            if (!id.toString().equalsIgnoreCase(value)) {
                throw invalid();
            }

            return id;
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw invalid();
        }
    }

    public static String requestKey(String key) {
        if (key == null
                || key.isBlank()
                || key.codePointCount(0, key.length()) < 8
                || key.codePointCount(0, key.length()) > 128
                || key.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }

        return key;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static LocalDate date(JsonNode body) {
        String value = text(body, "plannedDate");

        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new DateTimeException("Invalid date");
            }

            LocalDate date = LocalDate.parse(value);

            if (date.getYear() < 1) {
                throw new DateTimeException("Invalid year");
            }

            return date;
        } catch (DateTimeException ex) {
            throw new BusinessException(ErrorCode.INVALID_DATE);
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);

        if (value == null || !value.isString() || value.asText().isBlank()) {
            throw invalid();
        }

        return value.asText();
    }

    private static void requireFields(JsonNode body, Set<String> allowed) {
        if (body == null
                || !body.isObject()
                || !allowed.containsAll(body.propertyNames())) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}