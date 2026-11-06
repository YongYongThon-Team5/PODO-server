package com.podo.server.profile.input;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record ChildProfileInput(
        Set<Field> present,
        String nickname,
        String colorCode,
        String ageBandCode,
        UUID profileImageId,
        List<UUID> interestCategoryIds,
        List<UUID> bookGenreIds
) {

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public enum Field {
        NICKNAME, COLOR_CODE, AGE_BAND_CODE, PROFILE_IMAGE_ID, INTEREST_CATEGORY_IDS, BOOK_GENRE_IDS
    }

    public ChildProfileInput {
        present = Set.copyOf(present);
        interestCategoryIds = interestCategoryIds == null ? null : List.copyOf(interestCategoryIds);
        bookGenreIds = bookGenreIds == null ? null : List.copyOf(bookGenreIds);
    }

    public boolean has(Field field) {
        return present.contains(field);
    }

    public static ChildProfileInput create(JsonNode body) {
        return parse(body, true);
    }

    public static ChildProfileInput patch(JsonNode body) {
        return parse(body, false);
    }

    private static ChildProfileInput parse(JsonNode body, boolean create) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw invalid();
        }
        Set<Field> present = EnumSet.noneOf(Field.class);
        for (String name : body.propertyNames()) {
            present.add(switch (name) {
                case "nickname" -> Field.NICKNAME;
                case "colorCode" -> Field.COLOR_CODE;
                case "ageBandCode" -> Field.AGE_BAND_CODE;
                case "profileImageId" -> Field.PROFILE_IMAGE_ID;
                case "interestCategoryIds" -> Field.INTEREST_CATEGORY_IDS;
                case "bookGenreIds" -> Field.BOOK_GENRE_IDS;
                default -> throw invalid();
            });
        }
        if (create && !present.containsAll(EnumSet.of(
                Field.NICKNAME, Field.COLOR_CODE, Field.INTEREST_CATEGORY_IDS, Field.BOOK_GENRE_IDS))) {
            throw invalid();
        }
        return new ChildProfileInput(present,
                text(body.get("nickname"), 30, false),
                text(body.get("colorCode"), 64, false),
                text(body.get("ageBandCode"), 64, true),
                optionalUuid(body.get("profileImageId")),
                uuids(body.get("interestCategoryIds")),
                uuids(body.get("bookGenreIds")));
    }

    private static String text(JsonNode node, int maximum, boolean nullable) {
        if (node == null || (nullable && node.isNull())) {
            return null;
        }
        if (!node.isString()) {
            throw invalid();
        }
        String value = node.stringValue();
        if (value.isBlank() || value.codePointCount(0, value.length()) > maximum) {
            throw invalid();
        }
        return value;
    }

    private static UUID optionalUuid(JsonNode node) {
        return node == null || node.isNull() ? null : uuid(node);
    }

    private static UUID uuid(JsonNode node) {
        if (!node.isString() || !UUID_FORMAT.matcher(node.stringValue()).matches()) {
            throw invalid();
        }
        return UUID.fromString(node.stringValue());
    }

    private static List<UUID> uuids(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isArray()) {
            throw invalid();
        }
        List<UUID> values = new ArrayList<>(node.size());
        Set<UUID> unique = new HashSet<>();
        for (JsonNode element : node) {
            UUID value = uuid(element);
            if (!unique.add(value)) {
                throw invalid();
            }
            values.add(value);
        }
        return values;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
