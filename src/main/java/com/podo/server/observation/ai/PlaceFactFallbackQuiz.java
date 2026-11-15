package com.podo.server.observation.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

import static com.podo.server.observation.ai.ObservationAi.*;

/** 사진 AI가 실패한 경우, 저장된 장소 자료를 읽고 확인하는 기본 퀴즈. */
@Component
public class PlaceFactFallbackQuiz {
    public static final String INTRODUCTION =
            "사진 퀴즈 대신 장소 자료로 푸는 기본 퀴즈예요.";

    public Output generate(Input input) {
        var sources = input.sources().stream()
                .filter(s -> s.id() != null && s.factText() != null
                        && s.factText().strip().length() >= 8)
                .toList();
        if (sources.isEmpty()) {
            throw new GenerationException(FailureCode.SOURCE_NOT_READY);
        }
        if (input.photos().isEmpty()) {
            throw new GenerationException(FailureCode.STORAGE_FAILURE);
        }
        var questions = new ArrayList<Question>();
        for (int i = 0; i < 3; i++) {
            Source source = sources.get(i % sources.size());
            String fact = source.factText().strip();
            int points = fact.codePointCount(0, fact.length());
            int length = Math.min(12, points);
            // 한 자료뿐이어도 가능한 한 서로 다른 부분을 보여 준다.
            int start = sources.size() == 1 ? Math.min(i * length, points - length) : 0;
            String quote = fact.substring(fact.offsetByCodePoints(0, start),
                    fact.offsetByCodePoints(0, start + length));
            String display = quote.replaceAll("[\\s\\p{Cc}]+", " ").strip();
            String text = switch (i) {
                case 0 -> "자료에 ‘" + display + "’라는 말이 있어요.";
                case 1 -> "자료에 ‘" + display + "’라는 말이 없어요.";
                default -> "소개에서 ‘" + display + "’를 찾을 수 있어요.";
            };
            questions.add(new Question(text, i == 1 ? "X" : "O",
                    "장소 자료에서 가져온 글의 일부예요. 함께 찾아봐요.",
                    source.id(), quote, input.photos().get(0).position(),
                    "사진 대신 장소 설명 자료를 참고했어요.",
                    "부모님과 장소 설명을 읽어봐요.",
                    "따옴표 안의 말을 찾아봐요.",
                    "장소 설명 자료를 함께 읽어봐요."));
        }
        return new Output("READY", INTRODUCTION, List.copyOf(questions));
    }
}
