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
        int limit
) {
    public VisitFilter {
        statuses = List.copyOf(statuses);
    }

    public static VisitFilter parse(
            String status,
            String placeId,
            String rawLimit
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

            return new VisitFilter(
                    statuses,
                    placeId == null ? null : VisitInput.uuid(placeId),
                    limit
            );
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    public String hash() {
        return VisitInput.hash(
                statuses.stream().sorted().map(Enum::name).toList()
                        + "\n"
                        + placeId
        );
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}