package com.podo.server.quiz.repository;

import com.podo.server.observation.repository.ObservationRepository;
import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceAccessRepository;
import com.podo.server.quiz.domain.QuizAnswer;
import com.podo.server.quiz.domain.QuizContent;
import com.podo.server.quiz.domain.QuizHintView;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class QuizRepository {

    private final QuizSetRepository sets;
    private final QuizQuestionRepository questions;
    private final QuizAnswerRepository answers;
    private final QuizHintViewRepository hints;
    private final ObservationRepository observations;
    private final PlaceAccessRepository places;
    private final EntityManager entityManager;

    public Optional<QuizContent.Set> findSet(UUID id) {
        return sets.findContent(id);
    }

    public Optional<UUID> published(
            UUID placeId,
            UUID observationId,
            int afterVersion
    ) {
        return sets.findPublished(
                placeId,
                observationId,
                QuizContent.Status.PUBLISHED,
                afterVersion,
                PageRequest.of(0, 1)
        ).stream().findFirst();
    }

    public void lockSets(List<UUID> ids) {
        sets.lockAll(ids.stream().distinct().toList());
    }

    public Optional<QuizContent.Observation> latestObservation(UUID visitId) {
        return observations.findLatestLocked(
                        visitId,
                        PageRequest.of(0, 1)
                )
                .stream()
                .map(row -> new QuizContent.Observation(
                        row.getId(),
                        row.getVisitId(),
                        row.getGenerationStatus()
                ))
                .findFirst();
    }

    public Optional<QuizContent.Observation> observation(UUID id) {
        return observations.findContent(id);
    }

    public boolean publishedPlace(UUID id, boolean lock) {
        return lock
                ? places.findLockedById(id)
                .filter(place ->
                        Place.PUBLISHED.equals(place.getStatus()))
                .isPresent()
                : places.existsByIdAndStatus(id, Place.PUBLISHED);
    }

    public List<QuizContent.Question> questions(UUID setId) {
        return questions.findContent(setId).stream().map(question -> {
            var source = question.getSource();

            return new QuizContent.Question(
                    question.getId(),
                    question.getPosition(),
                    question.getQuestionText(),
                    question.getCorrectAnswer(),
                    question.getExplanation(),
                    source.getId(),
                    source.getPlaceId(),
                    source.getSourceType(),
                    source.getSourceUrl(),
                    source.getFactText(),
                    source.getCheckedAt(),
                    question.getHint1Text(),
                    question.getHint2Text(),
                    question.getObservationGuide(),
                    question.getReferenceImageUrl(),
                    question.getPhotoEvidenceText()
            );
        }).toList();
    }

    public Map<UUID, QuizContent.Answer> answers(
            UUID visitId,
            UUID setId
    ) {
        return answers.findCurrent(visitId, setId).stream()
                .collect(Collectors.toMap(
                        row -> row.getId().getQuestionId(),
                        row -> new QuizContent.Answer(
                                row.getSelectedAnswer(),
                                row.isCorrect()
                        )
                ));
    }

    public Set<QuizContent.HintKey> hints(UUID visitId, UUID setId) {
        return hints.findCurrent(visitId, setId).stream()
                .map(row -> new QuizContent.HintKey(
                        row.getId().getQuestionId(),
                        row.getId().getStep()
                ))
                .collect(Collectors.toSet());
    }

    public void answer(
            UUID visitId,
            UUID questionId,
            String answer,
            boolean correct,
            Instant now
    ) {
        entityManager.persist(
                new QuizAnswer(
                        visitId,
                        questionId,
                        answer,
                        correct,
                        now
                )
        );

        entityManager.flush();
    }

    public void viewHint(
            UUID visitId,
            UUID questionId,
            int step,
            Instant now
    ) {
        var id = new QuizHintView.Id(
                visitId,
                questionId,
                (short) step
        );

        if (!hints.existsById(id)) {
            entityManager.persist(
                    new QuizHintView(
                            visitId,
                            questionId,
                            step,
                            now
                    )
            );

            entityManager.flush();
        }
    }
}