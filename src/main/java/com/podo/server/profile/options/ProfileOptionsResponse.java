package com.podo.server.profile.options;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "ProfileOptions")
public record ProfileOptionsResponse(
        List<TagOption> interestCategories,
        List<TagOption> bookGenres,
        List<ProfileOptionsProperties.Option> ageBands,
        List<ProfileOptionsProperties.ColorOption> colors,
        ColorPolicy colorPolicy,
        PhotoUploadPolicy photoUploadPolicy
) {
    public record TagOption(UUID id, String code, String label, boolean active) { }
    public record ColorPolicy(boolean allowDuplicates) { }
    public record PhotoUploadPolicy(List<String> allowedMimeTypes, int maxBytes, int maxPixels) { }
}
