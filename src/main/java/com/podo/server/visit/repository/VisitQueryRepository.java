package com.podo.server.visit.repository;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.visit.domain.Visit;
import com.podo.server.visit.input.VisitFilter;
import com.podo.server.visit.pagination.VisitCursor;
import com.podo.server.visit.web.VisitResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class VisitQueryRepository {
    private final JdbcTemplate jdbc;

    private static final String SELECT = """
            SELECT v.*, p.name AS place_name,
              p.status = 'PUBLISHED' AS place_available,
              (SELECT count(*)::int
                 FROM app.quiz_answers a
                 JOIN app.quiz_questions q ON q.id = a.question_id
               WHERE a.visit_id = v.id
                 AND q.quiz_set_id = v.quiz_set_id) AS answered_count,
              CASE WHEN s.status = 'INVALIDATED' THEN NULL ELSE
                (SELECT count(*)::int
                   FROM app.quiz_answers a
                   JOIN app.quiz_questions q ON q.id = a.question_id
                 WHERE a.visit_id = v.id
                   AND q.quiz_set_id = v.quiz_set_id
                   AND a.is_correct)
              END AS correct_count,
              (SELECT count(*)::int
                 FROM app.quiz_hint_views h
                 JOIN app.quiz_questions q ON q.id = h.question_id
               WHERE h.visit_id = v.id
                 AND q.quiz_set_id = v.quiz_set_id) AS hint_usage_count
            FROM app.visits v
            JOIN app.places p ON p.id = v.place_id
            LEFT JOIN app.quiz_sets s ON s.id = v.quiz_set_id
            """;

    public VisitResponse owned(UUID accountId, UUID visitId) {
        return jdbc.query(
                        SELECT + " WHERE v.account_id = ? AND v.id = ?",
                        this::map,
                        accountId,
                        visitId
                )
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public List<VisitResponse> page(
            UUID accountId,
            VisitFilter filter,
            VisitCursor.Position cursor
    ) {
        String marks = String.join(
                ",",
                java.util.Collections.nCopies(filter.statuses().size(), "?")
        );

        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE v.account_id = ? AND v.status IN (")
                .append(marks)
                .append(")");

        List<Object> args = new ArrayList<>();
        args.add(accountId);

        filter.statuses().forEach(status -> args.add(status.name()));

        if (filter.placeId() != null) {
            sql.append(" AND v.place_id = ?");
            args.add(filter.placeId());
        }

        if (cursor != null) {
            sql.append(" AND (v.created_at, v.id) < (?, ?)");
            args.add(Timestamp.from(cursor.createdAt()));
            args.add(cursor.id());
        }

        sql.append(" ORDER BY v.created_at DESC, v.id DESC LIMIT ?");
        args.add(filter.limit() + 1);

        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    public void requirePublishedLocked(UUID placeId) {
        var statuses = jdbc.queryForList(
                "SELECT status FROM app.places WHERE id = ? FOR SHARE",
                String.class,
                placeId
        );

        if (statuses.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        if (!"PUBLISHED".equals(statuses.getFirst())) {
            throw new BusinessException(ErrorCode.PLACE_UNAVAILABLE);
        }
    }

    private VisitResponse map(ResultSet row, int index) throws SQLException {
        return new VisitResponse(
                row.getObject("id", UUID.class),
                row.getObject("place_id", UUID.class),
                row.getString("place_name"),
                row.getBoolean("place_available"),
                row.getObject("child_profile_id", UUID.class),
                row.getDate("planned_date").toLocalDate(),
                Visit.Source.valueOf(row.getString("source")),
                Visit.Status.valueOf(row.getString("status")),
                row.getObject("quiz_set_id", UUID.class),
                row.getInt("answered_count"),
                row.getObject("correct_count", Integer.class),
                row.getInt("hint_usage_count"),
                instant(row, "reported_at"),
                instant(row, "completed_at"),
                instant(row, "cancelled_at"),
                instant(row, "created_at"),
                instant(row, "updated_at")
        );
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}