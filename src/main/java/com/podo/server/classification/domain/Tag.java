package com.podo.server.classification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tags", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Kind kind;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String label;

    @Column(nullable = false)
    private boolean active;

    public enum Kind {
        INTEREST, BOOK_GENRE, PURPOSE
    }
}
