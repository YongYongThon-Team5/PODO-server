package com.podo.server.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "child_profiles", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildProfile {
    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "color_code", nullable = false, length = 64)
    private String colorCode;

    @Column(name = "age_band_code", length = 64)
    private String ageBandCode;

    @Column(name = "profile_image_id")
    private UUID profileImageId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "interest_category_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] interestCategoryIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "book_genre_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] bookGenreIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChildProfile(UUID id, UUID accountId, String nickname, String colorCode, String ageBandCode,
                        UUID profileImageId, UUID[] interestCategoryIds, UUID[] bookGenreIds, Instant now) {
        this.id = id;
        this.accountId = accountId;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        change(nickname, colorCode, ageBandCode, profileImageId, interestCategoryIds, bookGenreIds, now);
    }

    public void change(String nickname, String colorCode, String ageBandCode, UUID profileImageId,
                       UUID[] interestCategoryIds, UUID[] bookGenreIds, Instant now) {
        this.nickname = nickname;
        this.colorCode = colorCode;
        this.ageBandCode = ageBandCode;
        this.profileImageId = profileImageId;
        this.interestCategoryIds = interestCategoryIds.clone();
        this.bookGenreIds = bookGenreIds.clone();
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID[] getInterestCategoryIds() {
        return interestCategoryIds.clone();
    }

    public UUID[] getBookGenreIds() {
        return bookGenreIds.clone();
    }
}
