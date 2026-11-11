package com.podo.server.observation.ai;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static com.podo.server.observation.ai.ObservationAi.*;

class ObservationQuizValidatorTest {

    private final ObservationQuizValidator validator =
            new ObservationQuizValidator();

    private final UUID sourceId = UUID.randomUUID();

    private final String fact =
            "이 전시관에서는 용인의 역사와 문화를 소개합니다.";

    private Input input() {
        return new Input(
                "시험 장소",
                List.of(
                        new Photo(
                                1,
                                "image/jpeg",
                                new byte[]{1}
                        )
                ),
                List.of(new Source(sourceId, fact))
        );
    }

    private Question question(
            String text,
            UUID source,
            String quote,
            int photo
    ) {
        return new Question(
                text,
                "O",
                "자료의 내용과 일치합니다.",
                source,
                quote,
                photo,
                "사진의 안내판에 역사 전시라는 글자가 보입니다.",
                "안내판 제목을 살펴봐요.",
                "어느 지역을 소개하는지 찾아봐요.",
                "안내판을 관찰해요."
        );
    }

    private Output output(Question first) {
        return new Output(
                "READY",
                "사진과 자료를 함께 살펴봐요.",
                List.of(
                        first,
                        question(
                                "두 번째 명제",
                                sourceId,
                                fact,
                                1
                        ),
                        question(
                                "세 번째 명제",
                                sourceId,
                                fact,
                                1
                        )
                )
        );
    }

    private void invalid(Output output) {
        assertThatThrownBy(
                () -> validator.validate(input(), output)
        ).isInstanceOfSatisfying(
                GenerationException.class,
                ex -> assertThat(ex.code()).isEqualTo(
                        FailureCode.INVALID_AI_RESPONSE
                )
        );
    }

    @Test
    void acceptsTheContractWithExistingSourceAndPhoto() {
        assertThatCode(
                () -> validator.validate(
                        input(),
                        output(question(
                                "첫 번째 명제",
                                sourceId,
                                fact,
                                1
                        ))
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void rejectsInventedSourceIds() {
        invalid(output(question(
                "첫 번째 명제",
                UUID.randomUUID(),
                fact,
                1
        )));
    }

    @Test
    void rejectsQuotesAbsentFromTheSource() {
        invalid(output(question(
                "첫 번째 명제",
                sourceId,
                "자료에 없는 역사 이야기를 만들었습니다.",
                1
        )));
    }

    @Test
    void rejectsReferencesToMissingPhotos() {
        invalid(output(question(
                "첫 번째 명제",
                sourceId,
                fact,
                2
        )));
    }

    @Test
    void rejectsDuplicateStatementsAfterNormalization() {
        invalid(output(question(
                "두 번째 명제!",
                sourceId,
                fact,
                1
        )));
    }

    @Test
    void rejectsFewerThanThreeQuestions() {
        invalid(new Output(
                "READY",
                "소개",
                List.of(question(
                        "문제",
                        sourceId,
                        fact,
                        1
                ))
        ));
    }

    @Test
    void insufficientEvidenceRequiresAnotherPhotoRatherThanBlindRetry() {
        assertThatThrownBy(
                () -> validator.validate(
                        input(),
                        new Output(
                                "INSUFFICIENT_EVIDENCE",
                                "",
                                List.of()
                        )
                )
        ).isInstanceOfSatisfying(
                GenerationException.class,
                ex -> {
                    assertThat(ex.code()).isEqualTo(
                            FailureCode.INSUFFICIENT_EVIDENCE
                    );

                    assertThat(ex.code().retryable()).isFalse();
                }
        );
    }
}