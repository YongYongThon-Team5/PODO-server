package com.podo.server.profile.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.podo.server.profile.domain.ChildProfile;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "ChildProfile", requiredProperties = {"id", "nickname", "colorCode", "ageBandCode", "profileImageId",
        "interestCategoryIds", "bookGenreIds", "createdAt", "updatedAt"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChildProfileResponse(UUID id, @Schema(minLength = 1, maxLength = 30) String nickname,
        @Schema(minLength = 1, maxLength = 64) String colorCode,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64) String ageBandCode,
        @Schema(types = {"string", "null"}, format = "uuid") UUID profileImageId,
        @ArraySchema(uniqueItems = true) List<UUID> interestCategoryIds,
        @ArraySchema(uniqueItems = true) List<UUID> bookGenreIds,
        Instant createdAt, Instant updatedAt) {
    public static ChildProfileResponse from(ChildProfile profile) {
        return new ChildProfileResponse(profile.getId(), profile.getNickname(), profile.getColorCode(),
                profile.getAgeBandCode(), profile.getProfileImageId(), List.of(profile.getInterestCategoryIds()),
                List.of(profile.getBookGenreIds()), profile.getCreatedAt(), profile.getUpdatedAt());
    }
}
