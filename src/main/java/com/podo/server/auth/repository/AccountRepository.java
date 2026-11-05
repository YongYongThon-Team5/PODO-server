package com.podo.server.auth.repository;

import com.podo.server.auth.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByKakaoSubject(String kakaoSubject);

    @Modifying
    @Query(value = """
            INSERT INTO app.accounts (id, kakao_subject, status, created_at, updated_at)
            VALUES (:id, :subject, 'ACTIVE', :now, :now)
            ON CONFLICT (kakao_subject) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("subject") String subject, @Param("now") Instant now);
}
