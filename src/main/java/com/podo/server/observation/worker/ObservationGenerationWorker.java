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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
        long started = System.nanoTime();
        String stage = "snapshot";
        UUID visitId = null;
        Integer attempt = null;
        FailureCode failureCode = null;
        boolean completed = false;
        String exceptionType = "none";
        log.info("Observation worker event=started stage={} observationId={}", stage, lease.observationId());

        try {
            var snapshot = transactions.snapshot(lease);
            visitId = snapshot.visitId();
            attempt = snapshot.attemptCount();
            stage = "storage";
            log.info("Observation worker event=started stage={} observationId={} visitId={} attempt={} photoCount={}",
                    stage, lease.observationId(), visitId, attempt, snapshot.images().size());

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

            stage = "ai";
            Output output = ai.generate(input);

            stage = "validation";
            validator.validate(input, output);

            stage = "complete";
            transactions.complete(
                    lease,
                    snapshot,
                    input,
                    output
            );
            completed = true;
        } catch (GenerationException ex) {
            failureCode = ex.code();
            exceptionType = ex.getClass().getName();
            transactions.failed(lease, failureCode);
        } catch (BusinessException ex) {
            failureCode = FailureCode.STORAGE_FAILURE;
            exceptionType = ex.getClass().getName();
            transactions.failed(lease, failureCode);
        } catch (RuntimeException ex) {
            failureCode = FailureCode.INTERNAL_FAILURE;
            exceptionType = ex.getClass().getName();
            transactions.failed(lease, failureCode);
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (completed) {
                log.info("Observation worker event=completed stage={} observationId={} visitId={} attempt={} elapsedMs={}",
                        stage, lease.observationId(), visitId, attempt, elapsedMs);
            } else if (failureCode != null) {
                log.warn("Observation worker event=failed stage={} observationId={} visitId={} attempt={} "
                                + "elapsedMs={} failureCode={} exceptionType={}",
                        stage, lease.observationId(), visitId, attempt, elapsedMs, failureCode, exceptionType);
            }
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