package com.podo.server.visit.input;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.visit.domain.Visit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record VisitFilter(
        List<Visit.Status> statuses,
        UUID placeId,
        int limit,
        Sort sort
) {
    public enum Sort {
        CREATED_AT_DESC,
        COMPLETED_AT_DESC
    }

    public VisitFilter {
        statuses = List.copyOf(statuses);
    }

    public static VisitFilter parse(
            String status,
            String placeId,
            String rawLimit
    ) {
        return parse(status, placeId, rawLimit, null);
    }

    public static VisitFilter parse(
            String status,
            String placeId,
            String rawLimit,
            String rawSort
    ) {
        try {
            List<Visit.Status> statuses = status == null
                    ? List.of(Visit.Status.values())
                    : Arrays.stream(status.split(",", -1))
                    .map(Visit.Status::valueOf)
                    .sorted()
                    .toList();

            if (statuses.isEmpty()
                    || statuses.size() > 4
                    || statuses.stream().distinct().count() != statuses.size()) {
                throw invalid();
            }

            int limit = rawLimit == null ? 20 : Integer.parseInt(rawLimit);

            if (limit < 1
                    || limit > 100
                    || (rawLimit != null && !rawLimit.matches("[0-9]+"))) {
                throw invalid();
            }

            Sort sort = rawSort == null
                    ? Sort.CREATED_AT_DESC
                    : Sort.valueOf(rawSort);

            if (sort == Sort.COMPLETED_AT_DESC
                    && !statuses.equals(List.of(Visit.Status.COMPLETED))) {
                throw invalid();
            }

            return new VisitFilter(
                    statuses,
                    placeId == null ? null : VisitInput.uuid(placeId),
                    limit,
                    sort
            );
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    public String hash() {
        String value = statuses.stream().sorted().map(Enum::name).toList()
                + "\n" + placeId;

        // Keep existing creation-order cursors valid, including explicit default sort.
        if (sort != Sort.CREATED_AT_DESC) {
            value += "\n" + sort;
        }

        return VisitInput.hash(value);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
