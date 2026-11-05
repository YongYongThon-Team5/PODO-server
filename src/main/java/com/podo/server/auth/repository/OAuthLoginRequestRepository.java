package com.podo.server.auth.repository;

import com.podo.server.auth.domain.OAuthLoginRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OAuthLoginRequestRepository extends JpaRepository<OAuthLoginRequest, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from OAuthLoginRequest request where request.stateHash = :stateHash")
    Optional<OAuthLoginRequest> findLockedByStateHash(@Param("stateHash") String stateHash);

    @Modifying
    @Query("delete from OAuthLoginRequest request where request.expiresAt <= :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
