package com.podo.server.quiz.service;

import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.quiz.domain.QuizContent;
import com.podo.server.quiz.repository.QuizRepository;
import com.podo.server.quiz.web.QuizResponses;
import com.podo.server.quiz.web.QuizResponses.Availability;
import com.podo.server.visit.domain.Visit;
import com.podo.server.visit.repository.VisitQueryRepository;
import com.podo.server.visit.repository.VisitRepository;
import com.podo.server.visit.web.VisitResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuizService {
    private final AccountRepository accounts;
    private final VisitRepository visits;
    private final VisitQueryRepository visitQueries;
    private final QuizRepository quizzes;
    private final Clock clock;

    private record Context(
            QuizContent.Set set,
            List<QuizContent.Question> questions,
            boolean placeAvailable
    ) {}

    @Transactional
    public VisitResponse report(UUID accountId, UUID visitId) {
        Visit visit = lockVisit(accountId, visitId);

        if (visit.getStatus() == Visit.Status.CANCELLED) {
            throw error(ErrorCode.INVALID_VISIT_STATE);
        }

        if (visit.getStatus() != Visit.Status.PLANNED) {
            return visitQueries.owned(accountId, visitId);
        }

        if (!quizzes.publishedPlace(visit.getPlaceId(), true)) {
            throw error(ErrorCode.PLACE_UNAVAILABLE);
        }

        var observation = quizzes.latestObservation(visitId);

        if (observation.isPresent()
                && !"READY".equals(observation.get().generationStatus())) {
            throw error(ErrorCode.QUIZ_NOT_READY);
        }

        UUID observationId = observation
                .map(QuizContent.Observation::id)
                .orElse(null);

        UUID setId = quizzes.published(
                visit.getPlaceId(),
                observationId,
                0
        ).orElseThrow(() -> error(ErrorCode.QUIZ_NOT_READY));

        quizzes.lockSets(List.of(setId));

        var set = set(setId);

        if (set.status() != QuizContent.Status.PUBLISHED
                || !validContext(visit, set)
                || !ready(set, quizzes.questions(setId))) {
            throw error(ErrorCode.QUIZ_NOT_READY);
        }

        visit.startQuiz(setId, clock.instant());
        visits.flush();

        return visitQueries.owned(accountId, visitId);
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public QuizResponses.Progress get(UUID accountId, UUID visitId) {
        Visit visit = visits.findByIdAndAccountId(visitId, accountId)
                .orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));

        requireStarted(visit);

        var set = set(visit.getQuizSetId());
        requireContext(visit, set);

        return progress(
                visit,
                new Context(
                        set,
                        quizzes.questions(set.id()),
                        quizzes.publishedPlace(visit.getPlaceId(), false)
                )
        );
    }

    @Transactional
    public QuizResponses.AnswerResult answer(
            UUID accountId,
            UUID visitId,
            UUID questionId,
            String selected
    ) {
        if (selected == null || !List.of("O", "X").contains(selected)) {
            throw error(ErrorCode.INVALID_REQUEST);
        }

        Visit visit = lockVisit(accountId, visitId);
        Context context = currentLocked(visit);
        var question = question(context, questionId);

        var saved = quizzes.answers(
                visitId,
                context.set().id()
        ).get(questionId);

        if (saved != null) {
            if (!saved.selectedAnswer().equals(selected)) {
                throw error(ErrorCode.ANSWER_ALREADY_SUBMITTED);
            }

            return answerResult(visit, context, question, saved);
        }

        if (visit.getStatus() != Visit.Status.QUIZ_IN_PROGRESS) {
            throw error(ErrorCode.INVALID_VISIT_STATE);
        }

        requireUsable(context);

        quizzes.answer(
                visitId,
                questionId,
                selected,
                selected.equals(question.correctAnswer()),
                clock.instant()
        );

        var answers = quizzes.answers(visitId, context.set().id());

        if (answers.size() == 3) {
            visit.completeQuiz(clock.instant());
            visits.flush();
        }

        return answerResult(
                visit,
                context,
                question,
                answers.get(questionId)
        );
    }

    @Transactional
    public QuizResponses.HintResult hint(
            UUID accountId,
            UUID visitId,
            UUID questionId,
            int step
    ) {
        if (step != 1 && step != 2) {
            throw error(ErrorCode.INVALID_REQUEST);
        }

        Visit visit = lockVisit(accountId, visitId);
        Context context = currentLocked(visit);
        var question = question(context, questionId);

        requireUsable(context);

        boolean viewed = quizzes.hints(
                visitId,
                context.set().id()
        ).contains(new QuizContent.HintKey(questionId, step));

        if (!viewed) {
            if (visit.getStatus() != Visit.Status.QUIZ_IN_PROGRESS) {
                throw error(ErrorCode.INVALID_VISIT_STATE);
            }

            quizzes.viewHint(
                    visitId,
                    questionId,
                    step,
                    clock.instant()
            );
        }

        return new QuizResponses.HintResult(
                questionId,
                step,
                question.hint(step),
                quizzes.hints(visitId, context.set().id()).size()
        );
    }

    @Transactional
    public QuizResponses.Progress restart(UUID accountId, UUID visitId) {
        Visit visit = lockVisit(accountId, visitId);

        if (visit.getStatus() != Visit.Status.QUIZ_IN_PROGRESS) {
            throw error(ErrorCode.INVALID_VISIT_STATE);
        }

        boolean placeAvailable = quizzes.publishedPlace(
                visit.getPlaceId(),
                true
        );

        if (!placeAvailable) {
            throw error(ErrorCode.PLACE_UNAVAILABLE);
        }

        var original = set(visit.getQuizSetId());
        requireContext(visit, original);

        var nextId = quizzes.published(
                visit.getPlaceId(),
                original.observationId(),
                original.version()
        );

        quizzes.lockSets(
                nextId.map(id -> List.of(original.id(), id))
                        .orElseGet(() -> List.of(original.id()))
        );

        var current = set(original.id());
        requireContext(visit, current);

        Context context = new Context(
                current,
                quizzes.questions(current.id()),
                true
        );

        if (current.usable()) {
            if (!ready(current, context.questions())) {
                throw error(ErrorCode.QUIZ_NOT_READY);
            }

            return progress(visit, context);
        }

        if (current.status() != QuizContent.Status.INVALIDATED) {
            throw error(ErrorCode.QUIZ_NOT_READY);
        }

        var replacement = replacement(visit, current)
                .orElseThrow(() -> error(ErrorCode.QUIZ_NOT_READY));

        if (nextId.isEmpty() || !nextId.get().equals(replacement.id())) {
            throw error(ErrorCode.QUIZ_NOT_READY);
        }

        visit.restartQuiz(replacement.id(), clock.instant());
        visits.flush();

        return progress(
                visit,
                new Context(
                        replacement,
                        quizzes.questions(replacement.id()),
                        true
                )
        );
    }

    private Visit lockVisit(UUID accountId, UUID visitId) {
        if (accounts.findLockedById(accountId)
                .filter(account -> account.isActive())
                .isEmpty()) {
            throw error(ErrorCode.INVALID_TOKEN);
        }

        return visits.findOwnedLocked(accountId, visitId)
                .orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Context currentLocked(Visit visit) {
        requireStarted(visit);

        boolean available = quizzes.publishedPlace(
                visit.getPlaceId(),
                true
        );

        quizzes.lockSets(List.of(visit.getQuizSetId()));

        var set = set(visit.getQuizSetId());
        requireContext(visit, set);

        return new Context(
                set,
                quizzes.questions(set.id()),
                available
        );
    }

    private void requireStarted(Visit visit) {
        if ((visit.getStatus() != Visit.Status.QUIZ_IN_PROGRESS
                && visit.getStatus() != Visit.Status.COMPLETED)
                || visit.getQuizSetId() == null) {
            throw error(ErrorCode.INVALID_VISIT_STATE);
        }
    }

    private QuizContent.Set set(UUID id) {
        return quizzes.findSet(id)
                .orElseThrow(() -> error(ErrorCode.QUIZ_CONTENT_CHANGED));
    }

    private boolean validContext(Visit visit, QuizContent.Set set) {
        return set.placeId().equals(visit.getPlaceId())
                && (
                set.observationId() == null
                        || quizzes.observation(set.observationId())
                        .filter(observation ->
                                observation.visitId()
                                        .equals(visit.getId()))
                        .isPresent()
        );
    }

    private void requireContext(Visit visit, QuizContent.Set set) {
        if (!validContext(visit, set)) {
            throw error(ErrorCode.QUIZ_CONTENT_CHANGED);
        }
    }

    private boolean ready(
            QuizContent.Set set,
            List<QuizContent.Question> questions
    ) {
        return set.reviewed()
                && questions.size() == 3
                && questions.stream()
                .map(QuizContent.Question::position)
                .toList()
                .equals(List.of(1, 2, 3))
                && questions.stream()
                .allMatch(question -> question.ready(set.placeId()));
    }

    private Availability availability(Context context) {
        if (context.set().status() == QuizContent.Status.INVALIDATED) {
            return Availability.CORRECTION_REQUIRED;
        }

        return context.set().usable()
                && ready(context.set(), context.questions())
                ? Availability.READY
                : Availability.NOT_READY;
    }

    private void requireUsable(Context context) {
        if (!context.placeAvailable()) {
            throw error(ErrorCode.PLACE_UNAVAILABLE);
        }

        if (context.set().status() == QuizContent.Status.INVALIDATED) {
            throw error(ErrorCode.QUIZ_CONTENT_CHANGED);
        }

        if (availability(context) != Availability.READY) {
            throw error(ErrorCode.QUIZ_NOT_READY);
        }
    }

    private QuizContent.Question question(
            Context context,
            UUID questionId
    ) {
        return context.questions().stream()
                .filter(question -> question.id().equals(questionId))
                .findFirst()
                .orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Optional<QuizContent.Set> replacement(
            Visit visit,
            QuizContent.Set current
    ) {
        if (current.observationId() != null
                && quizzes.observation(current.observationId())
                .filter(observation ->
                        "READY".equals(observation.generationStatus()))
                .isEmpty()) {
            return Optional.empty();
        }

        return quizzes.published(
                        visit.getPlaceId(),
                        current.observationId(),
                        current.version()
                )
                .flatMap(quizzes::findSet)
                .filter(set -> validContext(visit, set))
                .filter(set ->
                        set.status() == QuizContent.Status.PUBLISHED
                                && ready(set, quizzes.questions(set.id())));
    }

    private QuizResponses.Progress progress(
            Visit visit,
            Context context
    ) {
        var answers = quizzes.answers(visit.getId(), context.set().id());
        var hints = quizzes.hints(visit.getId(), context.set().id());
        var availability = availability(context);

        var questions = context.questions().stream().map(question -> {
            var answer = answers.get(question.id());

            return new QuizResponses.Question(
                    question.id(),
                    question.position(),
                    question.text(),
                    question.observationGuide(),
                    question.referenceImageUrl(),
                    List.of(1, 2),
                    hints.stream()
                            .filter(hint ->
                                    hint.questionId().equals(question.id()))
                            .map(QuizContent.HintKey::step)
                            .sorted()
                            .toList(),
                    answer == null ? null : answer.selectedAnswer(),
                    answer != null && availability == Availability.READY
                            ? feedback(question, answer)
                            : null
            );
        }).toList();

        boolean restartAvailable =
                visit.getStatus() == Visit.Status.QUIZ_IN_PROGRESS
                        && context.placeAvailable()
                        && availability == Availability.CORRECTION_REQUIRED
                        && replacement(visit, context.set()).isPresent();

        return new QuizResponses.Progress(
                visit.getId(),
                context.set().id(),
                context.set().version(),
                availability,
                visit.getStatus(),
                context.set().introduction(),
                answers.size(),
                availability == Availability.READY
                        ? correctCount(answers)
                        : null,
                hints.size(),
                questions,
                restartAvailable
        );
    }

    private QuizResponses.AnswerResult answerResult(
            Visit visit,
            Context context,
            QuizContent.Question question,
            QuizContent.Answer answer
    ) {
        var answers = quizzes.answers(visit.getId(), context.set().id());
        boolean usable = availability(context) == Availability.READY;

        return new QuizResponses.AnswerResult(
                visit.getId(),
                question.id(),
                answer.selectedAnswer(),
                usable ? feedback(question, answer) : null,
                answers.size(),
                usable ? correctCount(answers) : null,
                visit.getStatus(),
                visit.getCompletedAt(),
                visit.getStatus() == Visit.Status.COMPLETED
                        ? visit.getPlaceId()
                        : null
        );
    }

    private int correctCount(Map<UUID, QuizContent.Answer> answers) {
        return (int) answers.values().stream()
                .filter(QuizContent.Answer::correct)
                .count();
    }

    private QuizResponses.Feedback feedback(
            QuizContent.Question question,
            QuizContent.Answer answer
    ) {
        return new QuizResponses.Feedback(
                question.correctAnswer(),
                answer.correct(),
                question.explanation(),
                "STORED_CONTENT",
                List.of(
                        new QuizResponses.Source(
                                question.sourceId(),
                                question.sourceType(),
                                question.sourceUrl(),
                                question.checkedAt()
                        )
                ),
                question.photoEvidenceText()
        );
    }

    private BusinessException error(ErrorCode code) {
        return new BusinessException(code);
    }
}