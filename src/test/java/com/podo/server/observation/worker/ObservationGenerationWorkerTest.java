package com.podo.server.observation.worker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.ai.ObservationQuizValidator;
import com.podo.server.observation.config.ObservationProperties;
import com.podo.server.observation.domain.ObservationData.Image;
import com.podo.server.observation.domain.ObservationData.Lease;
import com.podo.server.observation.repository.ObservationJobRepository;
import com.podo.server.observation.service.ObservationGenerationTransactions;
import com.podo.server.observation.storage.ObservationStorage;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static com.podo.server.observation.ai.ObservationAi.FailureCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObservationGenerationWorkerTest {

    private final ObservationProperties properties = mock(ObservationProperties.class);
    private final ObservationGenerationTransactions transactions = mock(ObservationGenerationTransactions.class);
    private final ObservationAi ai = mock(ObservationAi.class);
    private final ObservationQuizValidator validator = mock(ObservationQuizValidator.class);
    private final ObservationStorage storage = mock(ObservationStorage.class);
    private final Lease lease = new Lease(UUID.randomUUID(), UUID.randomUUID());
    private final UUID visitId = UUID.randomUUID();
    private final ObservationGenerationTransactions.Snapshot snapshot =
            new ObservationGenerationTransactions.Snapshot(lease.observationId(), visitId,
                    UUID.randomUUID(), UUID.randomUUID(), "private-place",
                    List.of(new Image(UUID.randomUUID(), 1, "private-storage-key", "image/jpeg", 12)),
                    List.of(), 2);
    private final Logger logger = (Logger) LoggerFactory.getLogger(ObservationGenerationWorker.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ObservationGenerationWorker worker = new ObservationGenerationWorker(
            properties, transactions, ai, validator, storage, mock(ObservationJobRepository.class), Clock.systemUTC());

    ObservationGenerationWorkerTest() {
        logs.start();
        logger.addAppender(logs);
        when(properties.enabled()).thenReturn(true);
        when(transactions.claim()).thenReturn(Optional.of(lease));
        when(transactions.snapshot(lease)).thenReturn(snapshot);
        when(storage.read("private-storage-key")).thenReturn(new byte[]{1, 2});
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void recordsProviderFailureWithJobContextAndPreservesFailureCode() {
        when(ai.generate(any())).thenThrow(new ObservationAi.GenerationException(UPSTREAM_UNAVAILABLE));

        worker.runOnce();

        verify(transactions).failed(lease, UPSTREAM_UNAVAILABLE);
        assertThat(messages()).contains("event=failed", "stage=ai", "failureCode=UPSTREAM_UNAVAILABLE");
        assertContextAndPrivacy();
    }

    @Test
    void recordsStorageStageForUnexpectedReadFailureWithoutExceptionMessage() {
        when(storage.read(anyString())).thenThrow(new IllegalStateException("private-exception-message"));

        worker.runOnce();

        verify(transactions).failed(lease, INTERNAL_FAILURE);
        verifyNoInteractions(ai);
        assertThat(messages()).contains("event=failed", "stage=storage", "failureCode=INTERNAL_FAILURE",
                "exceptionType=java.lang.IllegalStateException");
        assertContextAndPrivacy();
    }

    @Test
    void recordsSuccessAfterPersistingGeneratedOutput() {
        var output = new ObservationAi.Output("READY", "private-output", List.of());
        when(ai.generate(any())).thenReturn(output);

        worker.runOnce();

        verify(transactions).complete(eq(lease), eq(snapshot), any(), eq(output));
        verify(transactions, never()).failed(any(), any());
        assertThat(messages()).contains("event=completed", "stage=complete");
        assertContextAndPrivacy();
    }

    private String messages() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private void assertContextAndPrivacy() {
        assertThat(messages()).contains("observationId=" + lease.observationId(), "visitId=" + visitId,
                "attempt=2", "elapsedMs=").doesNotContain("private-place", "private-storage-key", "private-output",
                "private-exception-message", lease.token().toString());
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
