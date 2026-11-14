package com.podo.server.visit.repository;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceAccessRepository;
import com.podo.server.quiz.domain.QuizContent;
import com.podo.server.visit.domain.Visit;
import com.podo.server.visit.input.VisitFilter;
import com.podo.server.visit.pagination.VisitCursor;
import com.podo.server.visit.web.VisitResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class VisitQueryRepository {

    private final EntityManager entityManager;
    private final PlaceAccessRepository places;

    private static final String SELECT = """
            select visit, place.name, place.status, quiz.status
            from Visit visit
            join Place place on place.id = visit.placeId
            left join QuizSet quiz on quiz.id = visit.quizSetId
            """;

    public VisitResponse owned(UUID accountId, UUID visitId) {
        var rows = entityManager.createQuery(
                        SELECT
                                + " where visit.accountId = :accountId"
                                + " and visit.id = :id",
                        Tuple.class
                )
                .setParameter("accountId", accountId)
                .setParameter("id", visitId)
                .getResultList();

        return responses(rows).stream()
                .findFirst()
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public List<VisitResponse> page(
            UUID accountId,
            VisitFilter filter,
            VisitCursor.Position cursor
    ) {
        String sortColumn = filter.sort() == VisitFilter.Sort.COMPLETED_AT_DESC
                ? "visit.completedAt"
                : "visit.createdAt";

        String jpql = SELECT
                + " where visit.accountId = :accountId"
                + " and visit.status in :statuses";

        if (filter.placeId() != null) {
            jpql += " and visit.placeId = :placeId";
        }

        if (cursor != null) {
            jpql += """
                     and (
                         %s < :sortTime
                         or (
                             %s = :sortTime
                             and visit.id < :id
                         )
                     )
                    """.formatted(sortColumn, sortColumn);
        }

        var query = entityManager.createQuery(
                        jpql + " order by " + sortColumn + " desc, visit.id desc",
                        Tuple.class
                )
                .setParameter("accountId", accountId)
                .setParameter("statuses", filter.statuses())
                .setMaxResults(filter.limit() + 1);

        if (filter.placeId() != null) {
            query.setParameter("placeId", filter.placeId());
        }

        if (cursor != null) {
            query.setParameter("sortTime", cursor.sortTime());
            query.setParameter("id", cursor.id());
        }

        return responses(query.getResultList());
    }

    public void requirePublishedLocked(UUID placeId) {
        var place = places.findLockedById(placeId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!Place.PUBLISHED.equals(place.getStatus())) {
            throw new BusinessException(ErrorCode.PLACE_UNAVAILABLE);
        }
    }

    private List<VisitResponse> responses(List<Tuple> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = rows.stream()
                .map(row -> row.get(0, Visit.class).getId())
                .toList();

        Map<UUID, int[]> answerCounts = new HashMap<>();

        entityManager.createQuery("""
                select visit.id,
                       count(answer),
                       sum(case when answer.correct = true then 1 else 0 end)
                from QuizAnswer answer
                join answer.question question
                join Visit visit on visit.id = answer.id.visitId
                where visit.id in :ids
                  and question.quizSetId = visit.quizSetId
                group by visit.id
                """, Tuple.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(row -> answerCounts.put(
                        row.get(0, UUID.class),
                        new int[]{
                                row.get(1, Long.class).intValue(),
                                row.get(2, Long.class).intValue()
                        }
                ));

        Map<UUID, Integer> hintCounts = new HashMap<>();

        entityManager.createQuery("""
                select visit.id, count(hint)
                from QuizHintView hint
                join hint.question question
                join Visit visit on visit.id = hint.id.visitId
                where visit.id in :ids
                  and question.quizSetId = visit.quizSetId
                group by visit.id
                """, Tuple.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(row -> hintCounts.put(
                        row.get(0, UUID.class),
                        row.get(1, Long.class).intValue()
                ));

        return rows.stream().map(row -> {
            Visit visit = row.get(0, Visit.class);

            int[] counts = answerCounts.getOrDefault(
                    visit.getId(),
                    new int[]{0, 0}
            );

            Integer score =
                    row.get(3, QuizContent.Status.class)
                            == QuizContent.Status.INVALIDATED
                            ? null
                            : counts[1];

            return new VisitResponse(
                    visit.getId(),
                    visit.getPlaceId(),
                    row.get(1, String.class),
                    Place.PUBLISHED.equals(row.get(2, String.class)),
                    visit.getChildProfileId(),
                    visit.getPlannedDate(),
                    visit.getSource(),
                    visit.getStatus(),
                    visit.getQuizSetId(),
                    counts[0],
                    score,
                    hintCounts.getOrDefault(visit.getId(), 0),
                    visit.getReportedAt(),
                    visit.getCompletedAt(),
                    visit.getCancelledAt(),
                    visit.getCreatedAt(),
                    visit.getUpdatedAt()
            );
        }).toList();
    }
}
