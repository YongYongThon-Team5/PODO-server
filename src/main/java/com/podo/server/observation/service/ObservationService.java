package com.podo.server.observation.service;

import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.observation.ai.ObservationAi.FailureCode;
import com.podo.server.observation.config.ObservationProperties;
import com.podo.server.observation.domain.ObservationData.*;
import com.podo.server.observation.image.ObservationImageProcessor;
import com.podo.server.observation.repository.ObservationJobRepository;
import com.podo.server.observation.storage.ObservationStorage;
import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceAccessRepository;
import com.podo.server.visit.domain.Visit;
import com.podo.server.visit.repository.VisitRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
public class ObservationService {

    public record UploadFile(
            byte[] bytes,
            String contentType
    ) {
    }

    private final ObservationProperties properties;
    private final ObservationJobRepository jobs;
    private final AccountRepository accounts;
    private final VisitRepository visits;
    private final PlaceAccessRepository places;
    private final ObservationImageProcessor processor;
    private final ObservationStorage storage;
    private final Clock clock;

    @Transactional
    public State upload(
            UUID accountId,
            UUID visitId,
            String key,
            List<UploadFile> files
    ) {
        requireEnabled();

        if (key == null
                || !key.matches("[A-Za-z0-9_-]{8,128}")
                || files.isEmpty()
                || files.size() > 3) {
            throw error(ErrorCode.INVALID_REQUEST);
        }

        Visit visit = lockVisit(accountId, visitId);
        String hash = hash(files);

        var existing = jobs.byKey(visitId, key);

        if (existing.isPresent()) {
            if (!hash.equals(existing.get().requestHash())) {
                throw error(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }

            return state(existing.get());
        }

        requirePlanned(visit);

        var latest = jobs.latest(visitId);

        if (latest.filter(row ->
                List.of("UPLOADED", "GENERATING")
                        .contains(row.status())
        ).isPresent()) {
            throw error(ErrorCode.QUIZ_NOT_READY);
        }

        if (jobs.count(visitId) >= properties.maxUploadsPerVisit()) {
            throw error(ErrorCode.RATE_LIMITED);
        }

        if (jobs.sources(visit.getPlaceId()).isEmpty()) {
            throw new BusinessException(
                    ErrorCode.QUIZ_NOT_READY,
                    Map.of("reason", "SOURCE_NOT_READY")
            );
        }

        List<byte[]> normalized = files.stream()
                .map(file -> processor.process(
                        file.bytes(),
                        file.contentType()
                ))
                .toList();

        UUID id = UUID.randomUUID();

        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);

        if (latest.isPresent()
                && !now.isAfter(latest.get().createdAt())) {
            now = latest.get().createdAt()
                    .plus(1, ChronoUnit.MICROS);
        }

        List<Image> images = new ArrayList<>();

        for (int i = 0; i < normalized.size(); i++) {
            UUID imageId = UUID.randomUUID();

            images.add(new Image(
                    imageId,
                    i + 1,
                    imageId.toString(),
                    "image/jpeg",
                    normalized.get(i).length
            ));
        }

        var written = new ArrayList<String>();

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            return;
                        }

                        for (String storageKey : written) {
                            try {
                                storage.delete(storageKey);
                            } catch (RuntimeException ex) {
                                log.warn(
                                        "Observation rollback cleanup deferred: key={}",
                                        storageKey
                                );
                            }
                        }
                    }
                }
        );

        jobs.insert(
                id,
                visitId,
                key,
                hash,
                images.getFirst(),
                now
        );

        for (int i = 0; i < images.size(); i++) {
            Image image = images.get(i);

            storage.write(
                    image.storageKey(),
                    normalized.get(i)
            );

            written.add(image.storageKey());

            jobs.insertImage(id, image, now);
        }

        return state(
                jobs.find(id, false).orElseThrow()
        );
    }

    @Transactional(readOnly = true)
    public State get(
            UUID accountId,
            UUID visitId,
            UUID observationId
    ) {
        return state(
                owned(accountId, visitId, observationId)
        );
    }

    @Transactional(readOnly = true)
    public State latest(
            UUID accountId,
            UUID visitId
    ) {
        requireOwnedVisit(accountId, visitId);

        return state(
                jobs.latest(visitId).orElseThrow(
                        () -> error(ErrorCode.RESOURCE_NOT_FOUND)
                )
        );
    }

    @Transactional(readOnly = true)
    public Content image(
            UUID accountId,
            UUID visitId,
            UUID observationId,
            UUID imageId
    ) {
        owned(accountId, visitId, observationId);

        Image image = jobs.images(observationId).stream()
                .filter(row -> row.id().equals(imageId))
                .findFirst()
                .orElseThrow(
                        () -> error(ErrorCode.RESOURCE_NOT_FOUND)
                );

        return new Content(
                storage.read(image.storageKey()),
                image.mimeType()
        );
    }

    @Transactional
    public State retry(
            UUID accountId,
            UUID visitId,
            UUID observationId,
            int expectedAttempt
    ) {
        requireEnabled();

        if (expectedAttempt < 1 || expectedAttempt > 3) {
            throw error(ErrorCode.INVALID_REQUEST);
        }

        Visit visit = lockVisit(accountId, visitId);

        Row row = jobs.find(observationId, true)
                .filter(value ->
                        value.visitId().equals(visitId)
                )
                .orElseThrow(
                        () -> error(ErrorCode.RESOURCE_NOT_FOUND)
                );

        if (row.attemptCount() != expectedAttempt
                || !"FAILED".equals(row.status())) {
            return state(row);
        }

        requirePlanned(visit);

        if (jobs.latest(visitId)
                .filter(value ->
                        value.id().equals(observationId)
                )
                .isEmpty()) {
            throw error(ErrorCode.INVALID_VISIT_STATE);
        }

        if (!FailureCode.canRetry(row.failureCode())
                || row.attemptCount() >= properties.maxAttempts()) {
            throw error(ErrorCode.INVALID_VISIT_STATE);
        }

        jobs.retry(observationId, clock.instant());

        return state(
                jobs.find(observationId, false).orElseThrow()
        );
    }

    private Row owned(
            UUID accountId,
            UUID visitId,
            UUID observationId
    ) {
        requireOwnedVisit(accountId, visitId);

        return jobs.find(observationId, false)
                .filter(row -> row.visitId().equals(visitId))
                .orElseThrow(
                        () -> error(ErrorCode.RESOURCE_NOT_FOUND)
                );
    }

    private Visit requireOwnedVisit(
            UUID accountId,
            UUID visitId
    ) {
        return visits.findByIdAndAccountId(
                visitId,
                accountId
        ).orElseThrow(
                () -> error(ErrorCode.RESOURCE_NOT_FOUND)
        );
    }

    private Visit lockVisit(
            UUID accountId,
            UUID visitId
    ) {
        if (accounts.findLockedById(accountId)
                .filter(account -> account.isActive())
                .isEmpty()) {
            throw error(ErrorCode.INVALID_TOKEN);
        }

        return visits.findOwnedLocked(
                accountId,
                visitId
        ).orElseThrow(
                () -> error(ErrorCode.RESOURCE_NOT_FOUND)
        );
    }

    private void requirePlanned(Visit visit) {
        if (visit.getStatus() != Visit.Status.PLANNED) {
            throw error(ErrorCode.INVALID_VISIT_STATE);
        }

        if (places.findLockedById(visit.getPlaceId())
                .filter(place ->
                        Place.PUBLISHED.equals(place.getStatus())
                )
                .isEmpty()) {
            throw error(ErrorCode.PLACE_UNAVAILABLE);
        }
    }

    private State state(Row row) {
        var info = jobs.quizInfo(row.id());

        boolean latest = jobs.latest(row.visitId())
                .map(value -> value.id().equals(row.id()))
                .orElse(false);

        boolean planned = visits.findById(row.visitId())
                .map(visit ->
                        visit.getStatus() == Visit.Status.PLANNED
                                && places.existsByIdAndStatus(
                                visit.getPlaceId(),
                                Place.PUBLISHED
                        )
                )
                .orElse(false);

        boolean retryAvailable = properties.enabled()
                && planned
                && latest
                && "FAILED".equals(row.status())
                && row.attemptCount() < properties.maxAttempts()
                && FailureCode.canRetry(row.failureCode());

        boolean startAvailable = planned
                && latest
                && "READY".equals(row.status())
                && info.published();

        return new State(
                row.id(),
                row.visitId(),
                row.status(),
                row.attemptCount(),
                row.failureCode(),
                retryAvailable,
                startAvailable,
                "READY".equals(row.status())
                        ? info.introduction()
                        : null,
                jobs.images(row.id()).stream()
                        .map(image -> new ImageView(
                                image.id(),
                                image.position(),
                                "/api/visits/" + row.visitId()
                                        + "/observations/" + row.id()
                                        + "/images/" + image.id()
                        ))
                        .toList()
        );
    }

    private String hash(List<UploadFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance(
                    "SHA-256"
            );

            for (UploadFile file : files) {
                byte[] type = (
                        file.contentType() == null
                                ? ""
                                : file.contentType()
                ).getBytes(StandardCharsets.UTF_8);

                digest.update(
                        ByteBuffer.allocate(4)
                                .putInt(type.length)
                                .array()
                );

                digest.update(type);

                digest.update(
                        ByteBuffer.allocate(4)
                                .putInt(file.bytes().length)
                                .array()
                );

                digest.update(file.bytes());
            }

            return HexFormat.of().formatHex(
                    digest.digest()
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw error(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private BusinessException error(ErrorCode code) {
        return new BusinessException(code);
    }
}