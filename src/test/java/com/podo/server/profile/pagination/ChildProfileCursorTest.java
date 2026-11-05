package com.podo.server.profile.pagination;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildProfileCursorTest {

    private static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb");
    private static final UUID ID = UUID.fromString("cccccccc-4444-4555-8666-dddddddddddd");
    private static final Instant CREATED_AT = Instant.parse("2026-11-05T10:20:30.123456Z");

    @Test
    void roundTripsExactBoundaryWithoutLookingUpTheRow() {
        String cursor = ChildProfileCursor.encode(ACCOUNT, CREATED_AT, ID);
        assertThat(cursor).matches("[A-Za-z0-9_-]+").hasSizeLessThanOrEqualTo(256);
        ChildProfileCursor.Position position = ChildProfileCursor.decode(cursor, ACCOUNT);

        assertThat(position.createdAt()).isEqualTo(CREATED_AT);
        assertThat(position.id()).isEqualTo(ID);
    }

    @Test
    void rejectsCursorsFromAnotherAccount() {
        String cursor = ChildProfileCursor.encode(ACCOUNT, CREATED_AT, ID);
        assertInvalid(cursor, UUID.fromString("ffffffff-1111-4222-8333-bbbbbbbbbbbb"));
        assertInvalid(cursor, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-cursor!", "A", "====", "💜"})
    void rejectsMalformedOrBlankEncoding(String value) {
        assertInvalid(value, ACCOUNT);
    }

    @Test
    void rejectsMissingOversizedAndNonCanonicalEncoding() {
        assertInvalid(null, ACCOUNT);
        assertInvalid("A".repeat(257), ACCOUNT);
        String cursor = ChildProfileCursor.encode(ACCOUNT, CREATED_AT, ID);
        assertInvalid(cursor + "=", ACCOUNT);
        assertInvalid(" " + cursor, ACCOUNT);
        assertInvalid(cursor + "\n", ACCOUNT);
        // The final sextet has four padding bits: changing them must not produce an alias.
        String unpadded = raw("child-profiles\n1\n" + ACCOUNT + "\n2026-11-05T10:20:30.123Z\n" + ID);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(unpadded.charAt(unpadded.length() - 1));
        String alias = unpadded.substring(0, unpadded.length() - 1) + alphabet.charAt(last + 1);
        assertInvalid(alias, ACCOUNT);
    }

    @Test
    void rejectsWrongResourceVersionFieldsAndInvalidTimestamp() {
        for (String payload : new String[]{
                "visits\n1\n" + ACCOUNT + "\n" + CREATED_AT + "\n" + ID,
                "child-profiles\n2\n" + ACCOUNT + "\n" + CREATED_AT + "\n" + ID,
                "child-profiles\n1\n" + ACCOUNT + "\nnot-a-time\n" + ID,
                "child-profiles\n1\n" + ACCOUNT + "\n2026-13-40T00:00:00Z\n" + ID,
                "child-profiles\n1\n" + ACCOUNT + "\n" + CREATED_AT,
                "child-profiles\n1\n" + ACCOUNT + "\n" + CREATED_AT + "\n" + ID + "\nextra"
        }) {
            assertInvalid(raw(payload), ACCOUNT);
        }
    }

    @Test
    void rejectsNonCanonicalOrMalformedUuids() {
        assertInvalid(raw("child-profiles\n1\n" + ACCOUNT.toString().toUpperCase() + "\n" + CREATED_AT + "\n" + ID), ACCOUNT);
        assertInvalid(raw("child-profiles\n1\n" + ACCOUNT + "\n" + CREATED_AT + "\n" + ID.toString().toUpperCase()), ACCOUNT);
        assertInvalid(raw("child-profiles\n1\n" + ACCOUNT + "\n" + CREATED_AT + "\n1-1-1-1-1"), ACCOUNT);
    }

    private String raw(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private void assertInvalid(String cursor, UUID account) {
        assertThatThrownBy(() -> ChildProfileCursor.decode(cursor, account))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
    }
}
