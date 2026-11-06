package com.podo.server.quiz.repository;

import com.podo.server.quiz.domain.QuizContent;
import com.podo.server.quiz.domain.QuizSet;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizSetRepository extends JpaRepository<QuizSet, UUID> {

    @Query("""
            select new com.podo.server.quiz.domain.QuizContent$Set(
                quiz.id,
                quiz.placeId,
                quiz.observationId,
                quiz.introduction,
                quiz.version,
                quiz.status,
                quiz.reviewedBy,
                quiz.reviewedAt,
                quiz.publishedAt
            )
            from QuizSet quiz
            where quiz.id = :id
            """)
    Optional<QuizContent.Set> findContent(@Param("id") UUID id);

    @Query("""
            select quiz.id
            from QuizSet quiz
            where quiz.placeId = :placeId
              and quiz.status = :status
              and quiz.version > :afterVersion
              and (
                    (:observationId is null and quiz.observationId is null)
                    or quiz.observationId = :observationId
              )
            order by quiz.version desc
            """)
    List<UUID> findPublished(
            @Param("placeId") UUID placeId,
            @Param("observationId") UUID observationId,
            @Param("status") QuizContent.Status status,
            @Param("afterVersion") int afterVersion,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select quiz
            from QuizSet quiz
            where quiz.id in :ids
            order by quiz.id
            """)
    List<QuizSet> lockAll(@Param("ids") Collection<UUID> ids);
}