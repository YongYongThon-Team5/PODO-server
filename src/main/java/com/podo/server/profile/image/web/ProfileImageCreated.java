package com.podo.server.profile.image.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ProfileImageCreated(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID imageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"image/jpeg", "image/png", "image/webp"})
        String contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long sizeBytes
) { }
