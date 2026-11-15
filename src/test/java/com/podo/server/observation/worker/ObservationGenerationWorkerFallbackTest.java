package com.podo.server.observation.worker;

import com.podo.server.observation.ai.*;
import com.podo.server.observation.config.ObservationProperties;
import com.podo.server.observation.domain.ObservationData.*;
import com.podo.server.observation.repository.ObservationJobRepository;
import com.podo.server.observation.service.ObservationGenerationTransactions;
import com.podo.server.observation.service.ObservationGenerationTransactions.Snapshot;
import com.podo.server.observation.storage.ObservationStorage;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static com.podo.server.observation.ai.ObservationAi.*;

class ObservationGenerationWorkerFallbackTest {
    @Test
    void timeoutAndInvalidAndInsufficientOutputProduceBasicQuizInsteadOfFailedJob() {
        for (FailureCode code : List.of(FailureCode.UPSTREAM_TIMEOUT,
                FailureCode.INVALID_AI_RESPONSE, FailureCode.INSUFFICIENT_EVIDENCE,
                FailureCode.CONFIGURATION_ERROR, FailureCode.UPSTREAM_RATE_LIMITED)) {
            var props = mock(ObservationProperties.class);
            var transactions = mock(ObservationGenerationTransactions.class);
            var ai = mock(ObservationAi.class);
            var storage = mock(ObservationStorage.class);
            var jobs = mock(ObservationJobRepository.class);
            var lease = new Lease(UUID.randomUUID(), UUID.randomUUID());
            var snapshot = new Snapshot(lease.observationId(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "용인 공원",
                    List.of(new Image(UUID.randomUUID(), 1, "image-key", "image/jpeg", 1)),
                    List.of(new Source(UUID.randomUUID(), "공원에서는 다양한 나무와 꽃을 관찰할 수 있어요.")), 1);
            when(props.enabled()).thenReturn(true);
            when(transactions.claim()).thenReturn(Optional.of(lease));
            when(transactions.snapshot(lease)).thenReturn(snapshot);
            when(storage.read("image-key")).thenReturn(new byte[]{1});
            when(ai.generate(any())).thenThrow(new GenerationException(code));
            var worker = new ObservationGenerationWorker(props, transactions, ai,
                    new PlaceFactFallbackQuiz(), new ObservationQuizValidator(), storage, jobs, Clock.systemUTC());
            worker.runOnce();
            var output = org.mockito.ArgumentCaptor.forClass(Output.class);
            verify(transactions).complete(eq(lease), eq(snapshot), any(Input.class), output.capture());
            assertThat(output.getValue().questions()).hasSize(3);
            assertThat(output.getValue().introduction()).isEqualTo(PlaceFactFallbackQuiz.INTRODUCTION);
            verify(transactions, never()).failed(any(), any());
        }
    }
}
