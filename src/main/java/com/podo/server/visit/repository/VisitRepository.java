package com.podo.server.visit.repository;

import com.podo.server.visit.domain.Visit;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitRepository extends JpaRepository<Visit, UUID> {

    Optional<Visit> findByIdAndAccountId(
            UUID id,
            UUID accountId
    );

    Optional<Visit> findByAccountIdAndCreateRequestKey(
            UUID accountId,
            String createRequestKey
    );

    boolean existsByAccountIdAndStatusIn(
            UUID accountId,
            Collection<Visit.Status> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select visit from Visit visit
            where visit.id = :id
              and visit.accountId = :accountId
            """)
    Optional<Visit> findOwnedLocked(
            @Param("accountId") UUID accountId,
            @Param("id") UUID id
    );
}