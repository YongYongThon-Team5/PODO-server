package com.podo.server.visit.service;

import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.CursorResponse;
import com.podo.server.profile.repository.ChildProfileRepository;
import com.podo.server.visit.domain.Visit;
import com.podo.server.visit.input.VisitFilter;
import com.podo.server.visit.input.VisitInput;
import com.podo.server.visit.pagination.VisitCursor;
import com.podo.server.visit.repository.VisitQueryRepository;
import com.podo.server.visit.repository.VisitRepository;
import com.podo.server.visit.web.VisitResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VisitService {
    private final AccountRepository accounts;
    private final ChildProfileRepository profiles;
    private final VisitRepository visits;
    private final VisitQueryRepository queries;
    private final Clock clock;

    public record Created(boolean created, VisitResponse visit) {}

    @Transactional
    public Created create(
            UUID accountId,
            String key,
            VisitInput.Create input
    ) {
        VisitInput.requestKey(key);
        lockAccount(accountId);

        String hash = input.requestHash();
        var existing = visits.findByAccountIdAndCreateRequestKey(accountId, key);

        if (existing.isPresent()) {
            if (!existing.get().getCreateRequestHash().equals(hash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }

            return new Created(
                    false,
                    queries.owned(accountId, existing.get().getId())
            );
        }

        if (visits.existsByAccountIdAndStatusIn(
                accountId,
                List.of(Visit.Status.PLANNED, Visit.Status.QUIZ_IN_PROGRESS)
        )) {
            throw new BusinessException(ErrorCode.ACTIVE_VISIT_EXISTS);
        }

        if (input.childProfileId() != null
                && profiles.findByIdAndAccountId(
                input.childProfileId(),
                accountId
        ).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        queries.requirePublishedLocked(input.placeId());

        Visit visit = Visit.planned(
                accountId,
                input.placeId(),
                input.childProfileId(),
                input.plannedDate(),
                input.source(),
                key,
                hash,
                clock.instant()
        );

        visits.saveAndFlush(visit);

        return new Created(true, queries.owned(accountId, visit.getId()));
    }

    @Transactional(readOnly = true)
    public VisitResponse get(UUID accountId, UUID visitId) {
        return queries.owned(accountId, visitId);
    }

    @Transactional(readOnly = true)
    public CursorResponse<VisitResponse> list(
            UUID accountId,
            VisitFilter filter,
            String cursor
    ) {
        var position = cursor == null
                ? null
                : VisitCursor.decode(cursor, accountId, filter.hash());

        var rows = queries.page(accountId, filter, position);
        boolean more = rows.size() > filter.limit();
        var items = more ? rows.subList(0, filter.limit()) : rows;

        String next = more
                ? VisitCursor.encode(
                accountId,
                filter.hash(),
                items.getLast().createdAt(),
                items.getLast().id()
        )
                : null;

        return new CursorResponse<>(items, next);
    }

    @Transactional
    public VisitResponse changeDate(
            UUID accountId,
            UUID visitId,
            LocalDate date
    ) {
        lockAccount(accountId);

        Visit visit = ownedLocked(accountId, visitId);
        visit.changeDate(date, clock.instant());

        visits.flush();
        return queries.owned(accountId, visitId);
    }

    @Transactional
    public VisitResponse cancel(UUID accountId, UUID visitId) {
        lockAccount(accountId);

        Visit visit = ownedLocked(accountId, visitId);
        visit.cancel(clock.instant());

        visits.flush();
        return queries.owned(accountId, visitId);
    }

    private Visit ownedLocked(UUID accountId, UUID visitId) {
        return visits.findOwnedLocked(accountId, visitId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void lockAccount(UUID accountId) {
        if (accounts.findLockedById(accountId)
                .filter(account -> account.isActive())
                .isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }
}