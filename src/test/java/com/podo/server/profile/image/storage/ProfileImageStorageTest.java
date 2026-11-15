package com.podo.server.profile.image.storage;

import com.podo.server.global.exception.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileImageStorageTest {
    @TempDir
    Path root;

    @Test
    void neverOverwritesAnExistingImageAndAllowsRepeatedDeletion() throws IOException {
        var storage = new ProfileImageStorage(root.resolve("images").toString());
        String key = UUID.randomUUID().toString();

        storage.write(key, new byte[]{1, 2, 3});

        assertThatThrownBy(() -> storage.write(key, new byte[]{4}))
                .isInstanceOf(BusinessException.class);
        try (var stream = storage.open(key).orElseThrow()) {
            assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
        }

        storage.delete(key);
        storage.delete(key);

        assertThat(storage.open(key)).isEmpty();
    }

    @Test
    void rejectsLegacyDeletionKeysOutsideTheStorageDirectory() throws IOException {
        Path unrelated = Files.write(root.resolve("unrelated"), new byte[]{7});
        var storage = new ProfileImageStorage(root.resolve("images").toString());

        assertThat(storage.open("../unrelated")).isEmpty();
        assertThatThrownBy(() -> storage.delete("../unrelated")).isInstanceOf(BusinessException.class);

        assertThat(unrelated).exists();
    }
}
