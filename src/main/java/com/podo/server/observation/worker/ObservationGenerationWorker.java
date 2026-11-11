package com.podo.server.observation.worker;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.ai.ObservationQuizValidator;
import com.podo.server.observation.config.ObservationProperties;
import com.podo.server.observation.repository.ObservationJobRepository;
import com.podo.server.observation.service.ObservationGenerationTransactions;
import com.podo.server.observation.storage.ObservationStorage;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.podo.server.observation.ai.ObservationAi.*;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "podo.observation.worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ObservationGenerationWorker {

    private final ObservationProperties properties;
    private final ObservationGenerationTransactions transactions;
    private final ObservationAi ai;
    private final ObservationQuizValidator validator;
    private final ObservationStorage storage;
    private final ObservationJobRepository jobs;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${podo.observation.poll-interval:PT5S}"
    )
    public void runOnce() {
        if (!properties.enabled()) {
            return;
        }

        var next = transactions.claim();

        if (next.isEmpty()) {
            return;
        }

        var lease = next.get();

        try {
            var snapshot = transactions.snapshot(lease);

            var photos = snapshot.images().stream()
                    .map(image -> new Photo(
                            image.position(),
                            image.mimeType(),
                            storage.read(image.storageKey())
                    ))
                    .toList();

            var input = new Input(
                    snapshot.placeName(),
                    photos,
                    snapshot.sources()
            );

            Output output = ai.generate(input);

            validator.validate(input, output);

            transactions.complete(
                    lease,
                    snapshot,
                    input,
                    output
            );
        } catch (GenerationException ex) {
            transactions.failed(lease, ex.code());
        } catch (BusinessException ex) {
            transactions.failed(
                    lease,
                    FailureCode.STORAGE_FAILURE
            );
        } catch (RuntimeException ex) {
            log.error(
                    "Observation generation failed: observationId={}, exceptionType={}",
                    lease.observationId(),
                    ex.getClass().getName()
            );

            transactions.failed(
                    lease,
                    FailureCode.INTERNAL_FAILURE
            );
        }
    }

    @Scheduled(
            initialDelayString = "PT1H",
            fixedDelayString = "PT1H"
    )
    public void cleanOrphanFiles() {
        if (!properties.enabled()) {
            return;
        }

        var cutoff = clock.instant()
                .minus(Duration.ofHours(24));

        for (String key : storage.orphanCandidates(cutoff)) {
            try {
                if (!jobs.referencesFile(key)) {
                    storage.delete(key);
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Observation orphan cleanup deferred: key={}",
                        key
                );
            }
        }
    }
}