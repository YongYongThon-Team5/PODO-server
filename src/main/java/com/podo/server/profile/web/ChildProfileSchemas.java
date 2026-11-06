package com.podo.server.profile.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import java.util.List;
import java.util.UUID;

public final class ChildProfileSchemas {
    private ChildProfileSchemas() { }

    @Schema(name = "CreateChildProfile", requiredProperties = {"nickname", "colorCode", "interestCategoryIds", "bookGenreIds"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Create(@Schema(minLength = 1, maxLength = 30) String nickname,
            @Schema(minLength = 1, maxLength = 64) String colorCode,
            @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64) String ageBandCode,
            @Schema(types = {"string", "null"}, format = "uuid") UUID profileImageId,
            @ArraySchema(uniqueItems = true) List<UUID> interestCategoryIds,
            @ArraySchema(uniqueItems = true) List<UUID> bookGenreIds) { }

    @Schema(name = "PatchChildProfile", minProperties = 1, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Patch(@Schema(minLength = 1, maxLength = 30) String nickname,
            @Schema(minLength = 1, maxLength = 64) String colorCode,
            @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64) String ageBandCode,
            @Schema(types = {"string", "null"}, format = "uuid") UUID profileImageId,
            @ArraySchema(uniqueItems = true) List<UUID> interestCategoryIds,
            @ArraySchema(uniqueItems = true) List<UUID> bookGenreIds) { }
}
