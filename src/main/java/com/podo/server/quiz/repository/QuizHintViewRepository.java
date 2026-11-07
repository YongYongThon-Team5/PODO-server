package com.podo.server.quiz.repository;

import com.podo.server.quiz.domain.QuizHintView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizHintViewRepository
        extends JpaRepository<QuizHintView, QuizHintView.Id> {

    @Query("""
            select hint
            from QuizHintView hint
            join hint.question question
            where hint.id.visitId = :visitId
              and question.quizSetId = :setId
            """)
    List<QuizHintView> findCurrent(
            @Param("visitId") UUID visitId,
            @Param("setId") UUID setId
    );
}