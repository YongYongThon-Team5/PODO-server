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
    private final com.podo.server.observation.ai.PlaceFactFallbackQuiz fallback;
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

            Output output;
            long started = System.nanoTime();
            try {
                output = ai.generate(input);
                validator.validate(input, output);
            } catch (GenerationException ex) {
                if (ex.code() == FailureCode.WORKER_INTERRUPTED
                        || Thread.currentThread().isInterrupted()) {
                    throw ex;
                }
                log.warn("Observation AI fallback: observationId={}, failureCode={}, elapsedMs={}",
                        lease.observationId(), ex.code(),
                        (System.nanoTime() - started) / 1_000_000);
                output = fallback.generate(input);
                validator.validate(input, output);
            } catch (RuntimeException ex) {
                if (Thread.currentThread().isInterrupted()) {
                    throw ex;
                }
                log.warn("Observation AI fallback: observationId={}, exceptionType={}, elapsedMs={}",
                        lease.observationId(), ex.getClass().getSimpleName(),
                        (System.nanoTime() - started) / 1_000_000);
                output = fallback.generate(input);
                validator.validate(input, output);
            }
            log.info("Observation quiz prepared: observationId={}, basicQuiz={}, elapsedMs={}",
                    lease.observationId(),
                    com.podo.server.observation.ai.PlaceFactFallbackQuiz.INTRODUCTION.equals(output.introduction()),
                    (System.nanoTime() - started) / 1_000_000);

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
