package com.podo.server.visit.repository;

import com.podo.server.place.domain.Place;
import com.podo.server.visit.domain.Visit;
import com.podo.server.visit.pagination.CompletedPlaceCursor;
import com.podo.server.visit.web.CompletedPlaceResponse.Child;
import com.podo.server.visit.web.CompletedPlaceResponse.District;
import com.podo.server.visit.web.CompletedPlaceResponse.Item;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CompletedPlaceQueryRepository {
    private final EntityManager entityManager;

    private static final String SCOPE = """
            from Visit visit
            join Place place on place.id = visit.placeId
            where visit.accountId = :accountId
              and visit.status = :status
              and visit.completedAt <= :asOf
            """;

    public List<Item> page(
            UUID accountId,
            Instant asOf,
            CompletedPlaceCursor.Position cursor,
            int limit
    ) {
        String jpql = """
                select place.id, place.name, place.latitude, place.longitude,
                       place.districtCode, place.status,
                       min(visit.completedAt), max(visit.completedAt), count(visit),
                       sum(case when child.id is null then 1 else 0 end)
                from Visit visit
                join Place place on place.id = visit.placeId
                left join ChildProfile child
                    on child.id = visit.childProfileId and child.accountId = :accountId
                where visit.accountId = :accountId
                  and visit.status = :status
                  and visit.completedAt <= :asOf
                group by place.id, place.name, place.latitude, place.longitude,
                         place.districtCode, place.status
                """;

        if (cursor != null) {
            jpql += """
                    having max(visit.completedAt) < :lastCompletedAt
                        or (
                            max(visit.completedAt) = :lastCompletedAt
                            and place.id < :placeId
                        )
                    """;
        }

        var query = entityManager.createQuery(
                        jpql + " order by max(visit.completedAt) desc, place.id desc",
                        Tuple.class
                )
                .setParameter("accountId", accountId)
                .setParameter("status", Visit.Status.COMPLETED)
                .setParameter("asOf", asOf)
                .setMaxResults(limit + 1);

        if (cursor != null) {
            query.setParameter("lastCompletedAt", cursor.lastCompletedAt());
            query.setParameter("placeId", cursor.placeId());
        }

        return query.getResultList().stream()
                .map(row -> new Item(
                        row.get(0, UUID.class),
                        row.get(1, String.class),
                        row.get(2, BigDecimal.class),
                        row.get(3, BigDecimal.class),
                        row.get(4, String.class),
                        row.get(6, Instant.class),
                        row.get(7, Instant.class),
                        row.get(8, Long.class),
                        Place.PUBLISHED.equals(row.get(5, String.class)),
                        List.of(),
                        row.get(9, Long.class) > 0
                ))
                .toList();
    }

    public Map<UUID, List<Child>> children(
            UUID accountId,
            Instant asOf,
            List<UUID> ids
    ) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<Child>> result = new HashMap<>();

        entityManager.createQuery("""
                select distinct visit.placeId, child.id, child.colorCode
                from Visit visit
                join ChildProfile child
                    on child.id = visit.childProfileId and child.accountId = :accountId
                where visit.accountId = :accountId
                  and visit.status = :status
                  and visit.completedAt <= :asOf
                  and visit.placeId in :ids
                order by visit.placeId, child.id
                """, Tuple.class)
                .setParameter("accountId", accountId)
                .setParameter("status", Visit.Status.COMPLETED)
                .setParameter("asOf", asOf)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(row -> result
                        .computeIfAbsent(
                                row.get(0, UUID.class),
                                ignored -> new ArrayList<>()
                        )
                        .add(new Child(
                                row.get(1, UUID.class),
                                row.get(2, String.class)
                        )));

        result.replaceAll((id, values) -> List.copyOf(values));

        return result;
    }

    public List<District> districts(UUID accountId, Instant asOf) {
        return entityManager.createQuery(
                        "select place.districtCode, count(distinct place.id) "
                                + SCOPE
                                + """
                                 group by place.districtCode
                                 order by place.districtCode asc nulls last
                                """,
                        Tuple.class
                )
                .setParameter("accountId", accountId)
                .setParameter("status", Visit.Status.COMPLETED)
                .setParameter("asOf", asOf)
                .getResultList().stream()
                .map(row -> new District(
                        row.get(0, String.class),
                        row.get(1, Long.class)
                ))
                .toList();
    }
}