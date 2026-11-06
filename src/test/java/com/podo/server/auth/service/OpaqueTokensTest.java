package com.podo.server.auth.service;

import com.podo.server.auth.support.OpaqueTokens;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokensTest {

    @Test
    void generatesCanonical256BitTokensAndStoresOnlyTheirSha256Digest() {
        String token = OpaqueTokens.newToken();
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(OpaqueTokens.isToken(token)).isTrue();
        assertThat(OpaqueTokens.newToken()).isNotEqualTo(token);
        assertThat(OpaqueTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(OpaqueTokens.hash(token)).hasSize(64).doesNotContain(token);
        assertThat(OpaqueTokens.isToken(null)).isFalse();
        assertThat(OpaqueTokens.isToken(token + "=")).isFalse();
        assertThat(OpaqueTokens.isToken("A".repeat(42) + "B")).isFalse();
    }
}
