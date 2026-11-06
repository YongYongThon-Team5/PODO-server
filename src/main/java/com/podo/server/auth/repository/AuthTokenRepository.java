package com.podo.server.auth.repository;

import com.podo.server.auth.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

    // A scalar projection avoids caching token state before acquiring the session lock.
    @Query("select token.sessionId from AuthToken token where token.tokenHash = :hash")
    Optional<UUID> findSessionIdByTokenHash(@Param("hash") String hash);

    @Modifying(flushAutomatically = true)
    @Query("update AuthToken token set token.revokedAt = :now where token.sessionId = :sessionId")
    int revokeForSession(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    @Modifying
    @Query("""
            delete from AuthToken token where token.sessionId in
            (select session.id from AuthSession session
             where session.expiresAt <= :cutoff or session.revokedAt <= :cutoff)
            """)
    int deleteForRetiredSessions(@Param("cutoff") Instant cutoff);
}
