package com.podo.server.quiz.domain;

import com.podo.server.place.domain.PlaceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "quiz_questions", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizQuestion {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID quizSetId;

    @Column(nullable = false)
    private short position;

    @Column(nullable = false, columnDefinition = "text")
    private String questionText;

    @Column(nullable = false, columnDefinition = "text")
    private String correctAnswer;

    @Column(nullable = false, columnDefinition = "text")
    private String explanation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private PlaceSource source;

    @Column(name = "hint_1_text", columnDefinition = "text")
    private String hint1Text;

    @Column(name = "hint_2_text", columnDefinition = "text")
    private String hint2Text;

    @Column(columnDefinition = "text")
    private String observationGuide;

    @Column(columnDefinition = "text")
    private String referenceImageUrl;

    @Column(columnDefinition = "text")
    private String photoEvidenceText;

    @Column(columnDefinition = "text")
    private String aiModel;

    @Column(columnDefinition = "text")
    private String promptVersion;

    private Instant generatedAt;
}