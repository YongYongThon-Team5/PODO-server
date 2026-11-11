package com.podo.server.observation.storage;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.observation.config.ObservationProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LocalObservationStorage implements ObservationStorage {

    private static final int MAX_BYTES = 5_242_880;

    private final Path directory;

    public LocalObservationStorage(
            ObservationProperties properties
    ) {
        directory = properties.storageDirectory()
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public void write(String key, byte[] bytes) {
        Path destination = path(key);

        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw unavailable();
        }

        try {
            Files.createDirectories(directory);

            try (var output = Files.newOutputStream(
                    destination,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                output.write(bytes);
            }
        } catch (IOException ex) {
            throw unavailable();
        }
    }

    @Override
    public byte[] read(String key) {
        try (var stream = Files.newInputStream(
                path(key),
                LinkOption.NOFOLLOW_LINKS
        )) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);

            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw unavailable();
            }

            return bytes;
        } catch (IOException ex) {
            throw unavailable();
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException ex) {
            throw unavailable();
        }
    }

    @Override
    public List<String> orphanCandidates(Instant olderThan) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (var paths = Files.list(directory)) {
            return paths
                    .filter(path -> Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS
                    ))
                    .filter(path -> isKey(
                            path.getFileName().toString()
                    ))
                    .filter(path -> older(path, olderThan))
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException ex) {
            throw unavailable();
        }
    }

    private boolean older(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(
                    path,
                    LinkOption.NOFOLLOW_LINKS
            ).toInstant().isBefore(cutoff);
        } catch (IOException ex) {
            return false;
        }
    }

    private Path path(String key) {
        if (!isKey(key)) {
            throw unavailable();
        }

        return directory.resolve(key);
    }

    private boolean isKey(String key) {
        try {
            return key != null
                    && UUID.fromString(key).toString().equals(key);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE
        );
    }
}