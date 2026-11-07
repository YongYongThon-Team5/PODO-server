package com.podo.server.quiz.repository;

import com.podo.server.quiz.domain.QuizQuestion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizQuestionRepository
        extends JpaRepository<QuizQuestion, UUID> {

    @Query("""
            select question
            from QuizQuestion question
            join fetch question.source
            where question.quizSetId = :setId
            order by question.position
            """)
    List<QuizQuestion> findContent(@Param("setId") UUID setId);
}