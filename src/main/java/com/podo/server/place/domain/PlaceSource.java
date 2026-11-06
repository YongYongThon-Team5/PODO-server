package com.podo.server.place.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "place_sources", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceSource {
    public static final String ACTIVE = "ACTIVE";

    @Id
    private UUID id;

    @Column(name = "place_id", nullable = false)
    private UUID placeId;

    @Column(name = "source_type", nullable = false, columnDefinition = "text")
    private String sourceType;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "fact_text", nullable = false, columnDefinition = "text")
    private String factText;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(nullable = false, columnDefinition = "text")
    private String status;
}
