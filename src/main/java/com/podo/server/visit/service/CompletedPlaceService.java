package com.podo.server.visit.service;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.visit.pagination.CompletedPlaceCursor;
import com.podo.server.visit.repository.CompletedPlaceQueryRepository;
import com.podo.server.visit.web.CompletedPlaceResponse.District;
import com.podo.server.visit.web.CompletedPlaceResponse.Item;
import com.podo.server.visit.web.CompletedPlaceResponse.Page;
import com.podo.server.visit.web.CompletedPlaceResponse.Summary;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompletedPlaceService {
    private final CompletedPlaceQueryRepository queries;
    private final Clock clock;

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public Page list(
            UUID accountId,
            String rawLimit,
            String rawCursor
    ) {
        int limit = limit(rawLimit);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        var cursor = rawCursor == null
                ? null
                : CompletedPlaceCursor.decode(rawCursor, accountId, now);

        var asOf = cursor == null ? now : cursor.asOf();
        var rows = queries.page(accountId, asOf, cursor, limit);

        boolean hasNext = rows.size() > limit;
        var visible = rows.subList(0, Math.min(rows.size(), limit));

        var children = queries.children(
                accountId,
                asOf,
                visible.stream().map(Item::placeId).toList()
        );

        List<Item> items = visible.stream()
                .map(row -> new Item(
                        row.placeId(),
                        row.name(),
                        row.latitude(),
                        row.longitude(),
                        row.districtCode(),
                        row.firstCompletedAt(),
                        row.lastCompletedAt(),
                        row.completedVisitCount(),
                        row.placeAvailable(),
                        children.getOrDefault(row.placeId(), List.of()),
                        row.hasUnassignedVisit()
                ))
                .toList();

        String nextCursor = null;

        if (hasNext) {
            var last = items.getLast();

            nextCursor = CompletedPlaceCursor.encode(
                    accountId,
                    new CompletedPlaceCursor.Position(
                            asOf,
                            last.lastCompletedAt(),
                            last.placeId()
                    )
            );
        }

        var districts = queries.districts(accountId, asOf);

        long count = districts.stream()
                .mapToLong(District::completedPlaceCount)
                .sum();

        return new Page(
                items,
                nextCursor,
                new Summary(count, districts, asOf)
        );
    }

    private int limit(String value) {
        if (value == null) {
            return 20;
        }

        if (!value.matches("[0-9]{1,3}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        int limit = Integer.parseInt(value);

        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return limit;
    }
}