package com.podo.server.profile.image.service;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.profile.image.domain.ProfileImage;
import com.podo.server.profile.image.processing.ProfileImageProcessor;
import com.podo.server.profile.image.repository.ProfileImageRepository;
import com.podo.server.profile.image.storage.ProfileImageStorage;
import com.podo.server.profile.image.web.ProfileImageCreated;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileImageService {
    private final ProfileImageRepository images;
    private final ProfileImageProcessor processor;
    private final ProfileImageStorage storage;
    private final Clock clock;

    @Transactional
    public ProfileImageCreated upload(UUID accountId, byte[] bytes, String declaredContentType) {
        var processed = processor.process(bytes, declaredContentType);
        UUID id = UUID.randomUUID();
        String storageKey = id.toString();
        long sizeBytes = processed.bytes().length;
        images.saveAndFlush(new ProfileImage(id, accountId, storageKey, processed.contentType(), sizeBytes, clock.instant()));
        storage.write(storageKey, processed.bytes());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(storageKey);
                    } catch (BusinessException ex) {
                        log.warn("Could not remove a profile image after rollback. imageId={}", id);
                    }
                }
            }
        });
        return new ProfileImageCreated(id, processed.contentType(), sizeBytes);
    }

    @Transactional
    public ImageContent read(UUID accountId, UUID imageId) {
        var image = images.findByIdAndAccountId(imageId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        InputStream stream = storage.open(image.getStorageKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        stream.close();
                    } catch (IOException ex) {
                        log.warn("Could not close a profile image after a failed read transaction. imageId={}", imageId);
                    }
                }
            }
        });
        return new ImageContent(image.getMimeType(), image.getSizeBytes(), stream);
    }

    public record ImageContent(String contentType, long sizeBytes, InputStream stream) { }
}
