package com.podo.server.profile.image.repository;

import com.podo.server.profile.image.domain.ProfileImage;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileImageRepository extends JpaRepository<ProfileImage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<ProfileImage> findByIdAndAccountId(UUID id, UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select image from ProfileImage image
            where image.accountId = :accountId and image.id in :ids
            order by image.id
            """)
    List<ProfileImage> findOwnedLocked(@Param("accountId") UUID accountId,
                                      @Param("ids") Collection<UUID> ids);
}
