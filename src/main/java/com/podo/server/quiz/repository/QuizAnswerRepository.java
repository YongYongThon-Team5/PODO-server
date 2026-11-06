package com.podo.server.quiz.repository;

import com.podo.server.quiz.domain.QuizAnswer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizAnswerRepository
        extends JpaRepository<QuizAnswer, QuizAnswer.Id> {

    @Query("""
            select answer
            from QuizAnswer answer
            join answer.question question
            where answer.id.visitId = :visitId
              and question.quizSetId = :setId
            """)
    List<QuizAnswer> findCurrent(
            @Param("visitId") UUID visitId,
            @Param("setId") UUID setId
    );
}