package com.podo.server.observation.service;

import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.observation.ai.ObservationPrompt;
import com.podo.server.observation.ai.ObservationQuizValidator;
import com.podo.server.observation.config.ObservationProperties;
import com.podo.server.observation.domain.ObservationData.*;
import com.podo.server.observation.repository.ObservationJobRepository;
import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceAccessRepository;
import com.podo.server.place.repository.PlaceRepository;
import com.podo.server.visit.domain.Visit;
import com.podo.server.visit.repository.VisitRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.podo.server.observation.ai.ObservationAi.*;

@Service
@RequiredArgsConstructor
public class ObservationGenerationTransactions {

    public record Snapshot(
            UUID observationId,
            UUID visitId,
            UUID accountId,
            UUID placeId,
            String placeName,
            List<Image> images,
            List<Source> sources
    ) {
    }

    private final ObservationJobRepository jobs;
    private final ObservationProperties properties;
    private final AccountRepository accounts;
    private final VisitRepository visits;
    private final PlaceRepository places;
    private final PlaceAccessRepository placeAccess;
    private final ObservationQuizValidator validator;
    private final Clock clock;

    @Transactional
    public Optional<Lease> claim() {
        var now = clock.instant();

        jobs.expire(now);

        return jobs.claim(
                now,
                now.plus(properties.leaseDuration()),
                properties.model(),
                ObservationPrompt.VERSION,
                properties.maxAttempts()
        );
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(Lease lease) {
        Row row = jobs.find(
                lease.observationId(),
                false
        ).orElseThrow(
                () -> fail(FailureCode.VISIT_CHANGED)
        );

        if (!"GENERATING".equals(row.status())
                || !lease.token().equals(row.leaseToken())) {
            throw fail(FailureCode.WORKER_INTERRUPTED);
        }

        Visit visit = visits.findById(row.visitId())
                .orElseThrow(
                        () -> fail(FailureCode.VISIT_CHANGED)
                );

        if (visit.getStatus() != Visit.Status.PLANNED
                || accounts.findById(visit.getAccountId())
                .filter(account -> account.isActive())
                .isEmpty()) {
            throw fail(FailureCode.VISIT_CHANGED);
        }

        Place place = places.findById(visit.getPlaceId())
                .filter(value ->
                        Place.PUBLISHED.equals(value.getStatus())
                )
                .orElseThrow(
                        () -> fail(FailureCode.VISIT_CHANGED)
                );

        var images = jobs.images(row.id());
        var sources = jobs.sources(place.getId());

        if (images.isEmpty() || images.size() > 3) {
            throw fail(FailureCode.STORAGE_FAILURE);
        }

        if (sources.isEmpty()) {
            throw fail(FailureCode.SOURCE_NOT_READY);
        }

        return new Snapshot(
                row.id(),
                visit.getId(),
                visit.getAccountId(),
                place.getId(),
                place.getName(),
                images,
                sources
        );
    }

    @Transactional
    public void complete(
            Lease lease,
            Snapshot snapshot,
            Input input,
            Output output
    ) {
        validator.validate(input, output);

        if (accounts.findLockedById(snapshot.accountId())
                .filter(account -> account.isActive())
                .isEmpty()) {
            throw fail(FailureCode.VISIT_CHANGED);
        }

        Visit visit = visits.findOwnedLocked(
                snapshot.accountId(),
                snapshot.visitId()
        ).filter(value ->
                value.getStatus() == Visit.Status.PLANNED
        ).orElseThrow(
                () -> fail(FailureCode.VISIT_CHANGED)
        );

        if (placeAccess.findLockedById(visit.getPlaceId())
                .filter(place ->
                        Place.PUBLISHED.equals(place.getStatus())
                )
                .isEmpty()) {
            throw fail(FailureCode.VISIT_CHANGED);
        }

        Row row = jobs.find(
                lease.observationId(),
                true
        ).orElseThrow(
                () -> fail(FailureCode.VISIT_CHANGED)
        );

        if (!row.visitId().equals(snapshot.visitId())
                || !row.id().equals(snapshot.observationId())
                || !visit.getPlaceId().equals(snapshot.placeId())
                || !"GENERATING".equals(row.status())
                || !lease.token().equals(row.leaseToken())
                || row.leaseExpiresAt() == null
                || !row.leaseExpiresAt().isAfter(clock.instant())) {
            throw fail(FailureCode.WORKER_INTERRUPTED);
        }

        if (jobs.latest(visit.getId())
                .filter(value -> value.id().equals(row.id()))
                .isEmpty()) {
            throw fail(FailureCode.SUPERSEDED);
        }

        var usedIds = output.questions().stream()
                .map(Question::sourceId)
                .distinct()
                .sorted()
                .toList();

        jobs.lockSources(usedIds);

        var current = jobs.sources(visit.getPlaceId());

        var facts = current.stream()
                .collect(Collectors.toMap(
                        Source::id,
                        Source::factText
                ));

        var original = input.sources().stream()
                .collect(Collectors.toMap(
                        Source::id,
                        Source::factText
                ));

        if (usedIds.stream().anyMatch(id ->
                !original.containsKey(id)
                        || !original.get(id).equals(facts.get(id))
        )) {
            throw fail(FailureCode.SOURCE_CHANGED);
        }

        validator.validate(
                new Input(
                        input.placeName(),
                        input.photos(),
                        current
                ),
                output
        );

        jobs.insertQuiz(
                UUID.randomUUID(),
                visit.getPlaceId(),
                row.id(),
                output,
                snapshot.images(),
                properties.autoPublish(),
                clock.instant()
        );

        jobs.ready(row.id(), clock.instant());
    }

    @Transactional
    public void failed(Lease lease, FailureCode code) {
        jobs.fail(
                lease,
                code.name(),
                clock.instant()
        );
    }

    private GenerationException fail(FailureCode code) {
        return new GenerationException(code);
    }
}