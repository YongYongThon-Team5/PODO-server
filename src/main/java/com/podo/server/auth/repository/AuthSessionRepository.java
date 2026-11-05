package com.podo.server.auth.repository;

import com.podo.server.auth.domain.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.id = :id")
    Optional<AuthSession> findLockedById(@Param("id") UUID id);

    @Modifying
    @Query("delete from AuthSession session where session.expiresAt <= :cutoff or session.revokedAt <= :cutoff")
    int deleteRetiredBefore(@Param("cutoff") Instant cutoff);
}
