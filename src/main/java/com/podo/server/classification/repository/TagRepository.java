package com.podo.server.classification.repository;

import com.podo.server.classification.domain.Tag;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, UUID> {
    List<Tag> findAllByKindIn(Collection<Tag.Kind> kinds);
}
