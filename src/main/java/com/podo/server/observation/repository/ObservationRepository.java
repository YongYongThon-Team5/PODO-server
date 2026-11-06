package com.podo.server.observation.repository;

import com.podo.server.observation.domain.Observation;
import com.podo.server.quiz.domain.QuizContent;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObservationRepository
        extends JpaRepository<Observation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select observation
            from Observation observation
            where observation.visitId = :visitId
            order by observation.createdAt desc, observation.id desc
            """)
    List<Observation> findLatestLocked(
            @Param("visitId") UUID visitId,
            Pageable pageable
    );

    @Query("""
            select new com.podo.server.quiz.domain.QuizContent$Observation(
                observation.id,
                observation.visitId,
                observation.generationStatus
            )
            from Observation observation
            where observation.id = :id
            """)
    Optional<QuizContent.Observation> findContent(@Param("id") UUID id);
}