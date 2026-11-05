package com.podo.server.profile.input;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static com.podo.server.profile.input.ChildProfileInput.Field.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildProfileInputTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final UUID ID = UUID.fromString("abcdefab-1234-5678-9abc-def012345678");

    @Test
    void createAcceptsEmptySelectionsAndPreservesNicknameExactly() {
        ObjectNode body = validCreate().put("nickname", "  아이  ");
        ChildProfileInput input = ChildProfileInput.create(body);

        assertThat(input.nickname()).isEqualTo("  아이  ");
        assertThat(input.present()).containsExactlyInAnyOrder(
                NICKNAME, COLOR_CODE, INTEREST_CATEGORY_IDS, BOOK_GENRE_IDS);
        assertThat(input.interestCategoryIds()).isEmpty();
        assertThat(input.bookGenreIds()).isEmpty();
        assertThat(input.ageBandCode()).isNull();
        assertThat(input.profileImageId()).isNull();
    }

    @Test
    void createAcceptsOptionalNullsAndStandardUppercaseUuids() {
        ObjectNode body = validCreate().putNull("ageBandCode").putNull("profileImageId");
        body.withArray("interestCategoryIds").add(ID.toString().toUpperCase());
        ChildProfileInput input = ChildProfileInput.create(body);

        assertThat(input.has(AGE_BAND_CODE)).isTrue();
        assertThat(input.has(PROFILE_IMAGE_ID)).isTrue();
        assertThat(input.interestCategoryIds()).containsExactly(ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nickname", "colorCode", "interestCategoryIds", "bookGenreIds"})
    void createRejectsMissingOrNullRequiredFields(String field) {
        ObjectNode missing = validCreate();
        missing.remove(field);
        invalidCreate(missing);
        invalidCreate(validCreate().putNull(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "3", "\"text\"", "true"})
    void rejectsNonObjectBodies(String body) {
        assertInvalid(() -> ChildProfileInput.create(JSON.readTree(body)));
        assertInvalid(() -> ChildProfileInput.patch(JSON.readTree(body)));
    }

    @Test
    void rejectsMissingBodiesAndUnknownFields() {
        assertInvalid(() -> ChildProfileInput.create(null));
        assertInvalid(() -> ChildProfileInput.patch(null));
        invalidCreate(validCreate().put("unknown", "x"));
        assertInvalid(() -> ChildProfileInput.patch(JSON.readTree("{\"nickname\":\"아이\",\"unknown\":null}")));
        assertInvalid(() -> ChildProfileInput.patch(JSON.readTree("{}")));
    }

    @Test
    void patchDistinguishesOmissionNullClearingAndEmptySelection() {
        ChildProfileInput input = ChildProfileInput.patch(JSON.readTree("""
                {"ageBandCode":null,"profileImageId":null,"bookGenreIds":[]}
                """));

        assertThat(input.present()).containsExactlyInAnyOrder(AGE_BAND_CODE, PROFILE_IMAGE_ID, BOOK_GENRE_IDS);
        assertThat(input.has(NICKNAME)).isFalse();
        assertThat(input.has(COLOR_CODE)).isFalse();
        assertThat(input.has(INTEREST_CATEGORY_IDS)).isFalse();
        assertThat(input.ageBandCode()).isNull();
        assertThat(input.profileImageId()).isNull();
        assertThat(input.interestCategoryIds()).isNull();
        assertThat(input.bookGenreIds()).isEmpty();
    }

    @Test
    void patchParsesAllReplacementValuesWithoutChangingSelectionOrder() {
        ChildProfileInput input = ChildProfileInput.patch(JSON.readTree("""
                {
                  "nickname":"새 이름",
                  "colorCode":"COLOR_EXAMPLE",
                  "ageBandCode":"AGE_EXAMPLE",
                  "profileImageId":"ABCDEFAB-1234-5678-9ABC-DEF012345678",
                  "interestCategoryIds":["00000000-0000-0000-0000-000000000002","00000000-0000-0000-0000-000000000001"],
                  "bookGenreIds":["abcdefab-1234-5678-9abc-def012345678"]
                }
                """));

        assertThat(input.present()).containsExactlyInAnyOrder(ChildProfileInput.Field.values());
        assertThat(input.nickname()).isEqualTo("새 이름");
        assertThat(input.colorCode()).isEqualTo("COLOR_EXAMPLE");
        assertThat(input.ageBandCode()).isEqualTo("AGE_EXAMPLE");
        assertThat(input.profileImageId()).isEqualTo(ID);
        assertThat(input.interestCategoryIds()).containsExactly(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(input.bookGenreIds()).containsExactly(ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nickname", "colorCode", "interestCategoryIds", "bookGenreIds"})
    void patchRejectsNullForNonNullableFields(String field) {
        assertInvalid(() -> ChildProfileInput.patch(JSON.createObjectNode().putNull(field)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"nickname\":123}", "{\"colorCode\":true}", "{\"ageBandCode\":[]}",
            "{\"profileImageId\":123}", "{\"interestCategoryIds\":\"abcdefab-1234-5678-9abc-def012345678\"}",
            "{\"bookGenreIds\":{}}", "{\"bookGenreIds\":[null]}", "{\"bookGenreIds\":[1]}",
            "{\"bookGenreIds\":[true]}", "{\"bookGenreIds\":[{}]}"
    })
    void rejectsTypeCoercion(String body) {
        assertInvalid(() -> ChildProfileInput.patch(JSON.readTree(body)));
    }

    @Test
    void enforcesCodePointLengthsWithoutTrimming() {
        String nickname = "😀".repeat(30);
        assertThat(ChildProfileInput.create(validCreate().put("nickname", nickname)).nickname()).isEqualTo(nickname);
        invalidCreate(validCreate().put("nickname", nickname + "😀"));
        invalidCreate(validCreate().put("nickname", ""));
        invalidCreate(validCreate().put("nickname", " \t\n"));
        assertThat(ChildProfileInput.patch(JSON.createObjectNode().put("colorCode", "가".repeat(64))).colorCode())
                .hasSize(64);
        assertInvalid(() -> ChildProfileInput.patch(JSON.createObjectNode().put("colorCode", "가".repeat(65))));
        assertInvalid(() -> ChildProfileInput.patch(JSON.createObjectNode().put("ageBandCode", " ")));
        assertInvalid(() -> ChildProfileInput.patch(JSON.createObjectNode().put("ageBandCode", "a".repeat(65))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1-1-1-1-1", "abcdefab123456789abcdef012345678", " abcdefab-1234-5678-9abc-def012345678",
            "abcdefab-1234-5678-9abc-def012345678 ", "abcdefab-1234-5678-9abc-def01234567z", ""
    })
    void rejectsMalformedUuidsInImagesAndSelections(String value) {
        assertInvalid(() -> ChildProfileInput.patch(JSON.createObjectNode().put("profileImageId", value)));
        ObjectNode selection = JSON.createObjectNode();
        selection.putArray("interestCategoryIds").add(value);
        assertInvalid(() -> ChildProfileInput.patch(selection));
    }

    @Test
    void rejectsDuplicateUuidValuesRegardlessOfCaseInEitherSelection() {
        for (String field : List.of("interestCategoryIds", "bookGenreIds")) {
            ObjectNode body = JSON.createObjectNode();
            body.putArray(field).add(ID.toString()).add(ID.toString().toUpperCase());
            assertInvalid(() -> ChildProfileInput.patch(body));
        }
    }

    @Test
    void copiesCollectionsAndDoesNotExposeMutableParsedSelections() {
        var fields = EnumSet.of(INTEREST_CATEGORY_IDS);
        var ids = new ArrayList<>(List.of(ID));
        ChildProfileInput input = new ChildProfileInput(fields, null, null, null, null, ids, null);
        fields.clear();
        ids.clear();

        assertThat(input.present()).containsExactly(INTEREST_CATEGORY_IDS);
        assertThat(input.interestCategoryIds()).containsExactly(ID);
        assertThatThrownBy(() -> input.present().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> input.interestCategoryIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private ObjectNode validCreate() {
        ObjectNode body = JSON.createObjectNode().put("nickname", "아이").put("colorCode", "COLOR_EXAMPLE");
        body.putArray("interestCategoryIds");
        body.putArray("bookGenreIds");
        return body;
    }

    private void invalidCreate(ObjectNode body) {
        assertInvalid(() -> ChildProfileInput.create(body));
    }

    private void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
