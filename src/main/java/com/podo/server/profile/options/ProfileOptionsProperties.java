package com.podo.server.profile.options;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("podo.profile.options")
public record ProfileOptionsProperties(List<Option> ageBands, List<ColorOption> colors) {
    public ProfileOptionsProperties {
        ageBands = ageBands == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(ageBands));
        colors = colors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(colors));
    }

    public record Option(String code, String label, boolean active) { }

    public record ColorOption(String code, String label,
            @Schema(pattern = "^#[0-9A-Fa-f]{6}$") String hex, boolean active) { }
}
