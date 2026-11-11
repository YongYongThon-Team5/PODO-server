package com.podo.server.observation.ai;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import static com.podo.server.observation.ai.ObservationAi.*;

@Component
public class ObservationQuizValidator {

    public void validate(Input input, Output output) {
        if (output == null) {
            invalid();
        }

        if ("INSUFFICIENT_EVIDENCE".equals(output.result())) {
            throw new GenerationException(
                    FailureCode.INSUFFICIENT_EVIDENCE
            );
        }

        if (!"READY".equals(output.result())
                || !text(
                output.introduction(),
                ObservationTextLimits.INTRODUCTION
        )
                || output.questions() == null
                || output.questions().size() != 3) {
            invalid();
        }

        var sources = input.sources().stream()
                .collect(Collectors.toMap(
                        Source::id,
                        Source::factText
                ));

        Set<String> statements = new HashSet<>();

        for (Question question : output.questions()) {
            if (question == null || question.sourceId() == null) {
                invalid();
            }

            String fact = sources.get(question.sourceId());

            if (!questionText(question.text())
                    || !Set.of("O", "X").contains(
                    question.answer() == null
                            ? ""
                            : question.answer()
            )
                    || !text(
                    question.explanation(),
                    ObservationTextLimits.EXPLANATION
            )
                    || !text(
                    question.sourceQuote(),
                    ObservationTextLimits.SOURCE_QUOTE
            )
                    || question.sourceQuote().length() < 8
                    || fact == null
                    || !fact.contains(question.sourceQuote())
                    || question.imagePosition() < 1
                    || question.imagePosition() > input.photos().size()
                    || !text(
                    question.photoEvidenceText(),
                    ObservationTextLimits.PHOTO_EVIDENCE
            )
                    || !text(
                    question.hint1(),
                    ObservationTextLimits.HINT
            )
                    || !text(
                    question.hint2(),
                    ObservationTextLimits.HINT
            )
                    || question.hint1().strip().equals(
                    question.hint2().strip()
            )
                    || !text(
                    question.observationGuide(),
                    ObservationTextLimits.OBSERVATION_GUIDE
            )) {
                invalid();
            }

            String normalized = Normalizer.normalize(
                    question.text(),
                    Normalizer.Form.NFKC
            ).replaceAll(
                    "[\\p{P}\\s]",
                    ""
            ).toLowerCase(
                    java.util.Locale.ROOT
            );

            if (!statements.add(normalized)) {
                invalid();
            }
        }
    }

    private boolean questionText(String value) {
        return text(value, ObservationTextLimits.QUESTION)
                && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0;
    }

    private boolean text(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.codePointCount(0, value.length()) <= max;
    }

    private void invalid() {
        throw new GenerationException(
                FailureCode.INVALID_AI_RESPONSE
        );
    }
}