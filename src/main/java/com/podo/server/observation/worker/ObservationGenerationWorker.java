package com.podo.server.observation.worker;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.ai.ObservationQuizValidator;
import com.podo.server.observation.ai.PlaceFactFallbackQuiz;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.podo.server.observation.ai.ObservationAi.*;

@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
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
    private final PlaceFactFallbackQuiz fallback;
    private final ObservationQuizValidator validator;
    private final ObservationStorage storage;
    private final ObservationJobRepository jobs;
    private final Clock clock;

    /** 기존 호출 코드와 테스트의 생성자 호환성을 유지한다. */
    public ObservationGenerationWorker(
            ObservationProperties properties,
            ObservationGenerationTransactions transactions,
            ObservationAi ai,
            ObservationQuizValidator validator,
            ObservationStorage storage,
            ObservationJobRepository jobs,
            Clock clock
    ) {
        this(
                properties,
                transactions,
                ai,
                new PlaceFactFallbackQuiz(),
                validator,
                storage,
                jobs,
                clock
        );
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
        );
    }

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

        log.info(
                "Observation worker event=started stage={} observationId={}",
                stage,
                lease.observationId()
        );

        try {
            var snapshot = transactions.snapshot(lease);
            visitId = snapshot.visitId();
            attempt = snapshot.attemptCount();
            stage = "storage";

            log.info(
                    "Observation worker event=started stage={} observationId={} visitId={} attempt={} photoCount={}",
                    stage,
                    lease.observationId(),
                    visitId,
                    attempt,
                    snapshot.images().size()
            );

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
            Output output;

            try {
                output = ai.generate(input);

                stage = "validation";
                validator.validate(input, output);
            } catch (GenerationException ex) {
                if (
                        ex.code() == FailureCode.WORKER_INTERRUPTED
                                || Thread.currentThread().isInterrupted()
                                || input.sources().isEmpty()
                ) {
                    throw ex;
                }

                stage = "fallback";

                log.warn(
                        "Observation AI fallback: observationId={}, visitId={}, attempt={}, failureCode={}, elapsedMs={}",
                        lease.observationId(),
                        visitId,
                        attempt,
                        ex.code(),
                        elapsedMillis(started)
                );

                output = fallback.generate(input);
                validator.validate(input, output);
            } catch (RuntimeException ex) {
                if (
                        Thread.currentThread().isInterrupted()
                                || input.sources().isEmpty()
                ) {
                    throw ex;
                }

                stage = "fallback";

                log.warn(
                        "Observation AI fallback: observationId={}, visitId={}, attempt={}, exceptionType={}, elapsedMs={}",
                        lease.observationId(),
                        visitId,
                        attempt,
                        ex.getClass().getName(),
                        elapsedMillis(started)
                );

                output = fallback.generate(input);
                validator.validate(input, output);
            }

            log.info(
                    "Observation quiz prepared: observationId={}, basicQuiz={}, elapsedMs={}",
                    lease.observationId(),
                    PlaceFactFallbackQuiz.INTRODUCTION.equals(
                            output.introduction()
                    ),
                    elapsedMillis(started)
            );

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
            long elapsedMs = elapsedMillis(started);

            if (completed) {
                log.info(
                        "Observation worker event=completed stage={} observationId={} visitId={} attempt={} elapsedMs={}",
                        stage,
                        lease.observationId(),
                        visitId,
                        attempt,
                        elapsedMs
                );
            } else if (failureCode != null) {
                log.warn(
                        "Observation worker event=failed stage={} observationId={} visitId={} attempt={} "
                                + "elapsedMs={} failureCode={} exceptionType={}",
                        stage,
                        lease.observationId(),
                        visitId,
                        attempt,
                        elapsedMs,
                        failureCode,
                        exceptionType
                );
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