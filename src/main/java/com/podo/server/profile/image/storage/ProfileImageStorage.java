package com.podo.server.profile.image.storage;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProfileImageStorage {
    private static final Logger log = LoggerFactory.getLogger(ProfileImageStorage.class);
    private static final Pattern KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final Path directory;

    public ProfileImageStorage(@Value("${podo.profile.image-directory}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public void write(String storageKey, byte[] content) {
        if (!KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Invalid generated profile image key");
        }
        try {
            Files.createDirectories(directory);
            Path staged = Files.createTempFile(directory, ".upload-", ".tmp");
            try {
                Files.write(staged, content);
                Files.move(staged, directory.resolve(storageKey));
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException exception) {
            throw unavailable("write", exception);
        }
    }

    public Optional<InputStream> open(String storageKey) {
        if (!KEY.matcher(storageKey).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(directory.resolve(storageKey), LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw unavailable("read", exception);
        }
    }

    public void delete(String storageKey) {
        if (storageKey == null || !KEY.matcher(storageKey).matches()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            Files.deleteIfExists(directory.resolve(storageKey));
        } catch (IOException exception) {
            throw unavailable("delete", exception);
        }
    }

    private BusinessException unavailable(String operation, IOException exception) {
        log.warn("Profile image storage {} failed", operation, exception);
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
