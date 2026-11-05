package com.podo.server.classification.repository;

import com.podo.server.classification.domain.Tag;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends JpaRepository<Tag, UUID> {
    List<Tag> findAllByKindIn(Collection<Tag.Kind> kinds);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select tag from Tag tag where tag.id in :ids order by tag.id")
    List<Tag> findAllForValidation(@Param("ids") Collection<UUID> ids);
}
