package com.podo.server.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "quiz_sets", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizSet {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID placeId;

    private UUID observationId;

    @Column(columnDefinition = "text")
    private String introduction;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private QuizContent.Status status;

    @Column(columnDefinition = "text")
    private String reviewedBy;

    private Instant reviewedAt;

    private Instant publishedAt;

    private Instant invalidatedAt;
}