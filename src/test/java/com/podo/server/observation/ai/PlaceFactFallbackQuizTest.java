package com.podo.server.observation.ai;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.podo.server.observation.ai.ObservationAi.*;

class PlaceFactFallbackQuizTest {
    private final PlaceFactFallbackQuiz fallback = new PlaceFactFallbackQuiz();
    private final ObservationQuizValidator validator = new ObservationQuizValidator();

    private Input input(String fact) {
        return new Input("용인 장소", List.of(new Photo(1, "image/jpeg", new byte[]{1})),
                List.of(new Source(UUID.randomUUID(), fact)));
    }

    @Test
    void producesThreeValidSourceReadingQuestionsWithoutClaimingPhotoEvidence() {
        Input input = input("용인시의 이 공원에서는 다양한 나무와 꽃을 함께 관찰할 수 있습니다.");
        Output output = fallback.generate(input);
        validator.validate(input, output);
        assertThat(output.result()).isEqualTo("READY");
        assertThat(output.questions()).hasSize(3);
        assertThat(output.questions()).extracting(Question::answer).containsExactly("O", "X", "O");
        assertThat(output.introduction()).contains("기본 퀴즈");
        for (Question q : output.questions()) {
            assertThat(input.sources().getFirst().factText()).contains(q.sourceQuote());
            assertThat(q.photoEvidenceText()).contains("사진 대신");
        }
    }

    @Test
    void handlesMinimumLengthSourceAndWhitespaceWithoutDuplicateStatements() {
        for (String fact : List.of("가나다라마바사아", "가나다\n라마바사아", "🌳🌳🌳🌳🌳🌳🌳🌳")) {
            Input input = input(fact);
            Output output = fallback.generate(input);
            validator.validate(input, output);
            assertThat(output.questions()).extracting(Question::text).doesNotHaveDuplicates();
        }
    }

    @Test
    void doesNotInventSourceWhenNoUsableFactExists() {
        assertThatThrownBy(() -> fallback.generate(input("짧음")))
                .isInstanceOfSatisfying(GenerationException.class,
                        ex -> assertThat(ex.code()).isEqualTo(FailureCode.SOURCE_NOT_READY));
    }
}
