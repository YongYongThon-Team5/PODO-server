package com.podo.server.profile.repository;

import com.podo.server.profile.domain.ChildProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChildProfileRepository extends JpaRepository<ChildProfile, UUID> {
    Optional<ChildProfile> findByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByAccountIdAndColorCode(UUID accountId, String colorCode);

    boolean existsByAccountIdAndColorCodeAndIdNot(UUID accountId, String colorCode, UUID id);

    boolean existsByProfileImageId(UUID imageId);

    List<ChildProfile> findByAccountIdOrderByCreatedAtAscIdAsc(UUID accountId, Pageable pageable);

    @Query("""
            select profile from ChildProfile profile
            where profile.accountId = :accountId
              and (profile.createdAt > :createdAt
                   or (profile.createdAt = :createdAt and profile.id > :id))
            order by profile.createdAt asc, profile.id asc
            """)
    List<ChildProfile> findPageAfter(@Param("accountId") UUID accountId,
                                    @Param("createdAt") Instant createdAt,
                                    @Param("id") UUID id, Pageable pageable);
}
