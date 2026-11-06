package com.podo.server.place.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "places", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Place {

    public static final String PUBLISHED = "PUBLISHED";

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String address;

    @Column(columnDefinition = "numeric")
    private BigDecimal latitude;

    @Column(columnDefinition = "numeric")
    private BigDecimal longitude;

    @Column(name = "district_code", length = 64)
    private String districtCode;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "main_activities", columnDefinition = "text[]")
    private String[] mainActivities;

    @Column(nullable = false, columnDefinition = "text")
    private String environment;

    @Column(name = "indoor_only_available")
    private Boolean indoorOnlyAvailable;

    @Column(name = "opening_hours", columnDefinition = "text")
    private String openingHours;

    @Column(name = "fee_info", columnDefinition = "text")
    private String feeInfo;

    @Column(name = "target_age_info", columnDefinition = "text")
    private String targetAgeInfo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] amenities;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] cautions;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(
            name = "interest_category_ids",
            nullable = false,
            columnDefinition = "uuid[]"
    )
    private UUID[] interestCategoryIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(
            name = "book_genre_ids",
            nullable = false,
            columnDefinition = "uuid[]"
    )
    private UUID[] bookGenreIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(
            name = "purpose_ids",
            nullable = false,
            columnDefinition = "uuid[]"
    )
    private UUID[] purposeIds;

    @Column(name = "map_place_url", columnDefinition = "text")
    private String mapPlaceUrl;

    @Column(name = "map_directions_url", columnDefinition = "text")
    private String mapDirectionsUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}