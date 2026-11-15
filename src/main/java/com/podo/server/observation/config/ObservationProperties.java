package com.podo.server.observation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("podo.observation")
public record ObservationProperties(
        @DefaultValue("false")
        boolean enabled,

        @DefaultValue("true")
        boolean autoPublish,

        @NotNull
        @DefaultValue("./var/observations")
        Path storageDirectory,

        @DefaultValue("")
        String apiKey,

        @NotBlank
        @DefaultValue("gemini-3.8-flash")
        String model,

        @NotNull
        @DefaultValue("5s")
        Duration connectTimeout,

        @NotNull
        @DefaultValue("180s")
        Duration requestTimeout,

        @NotNull
        @DefaultValue("5m")
        Duration leaseDuration,

        @NotNull
        @DefaultValue("5s")
        Duration pollInterval,

        @Min(1)
        @Max(3)
        @DefaultValue("3")
        int maxAttempts,

        @Min(1)
        @Max(5)
        @DefaultValue("3")
        int maxUploadsPerVisit
) {

    @AssertTrue(message = "AI 기능을 활성화하려면 API 키가 필요합니다.")
    public boolean isKeyConfigured() {
        return !enabled
                || (apiKey != null && !apiKey.isBlank());
    }

    @AssertTrue(message = "AI 연결·요청·작업 점유·조회 시간을 확인해 주세요.")
    public boolean isTimingValid() {
        return connectTimeout != null
                && requestTimeout != null
                && leaseDuration != null
                && pollInterval != null
                && connectTimeout.compareTo(Duration.ZERO) > 0
                && connectTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && requestTimeout.compareTo(Duration.ZERO) > 0
                && requestTimeout.compareTo(Duration.ofSeconds(180)) <= 0
                && leaseDuration.compareTo(
                requestTimeout.plusSeconds(30)
        ) >= 0
                && leaseDuration.compareTo(Duration.ofMinutes(15)) <= 0
                && pollInterval.compareTo(Duration.ofSeconds(1)) >= 0
                && pollInterval.compareTo(Duration.ofMinutes(1)) <= 0;
    }

    @Override
    public String toString() {
        return "ObservationProperties[enabled=" + enabled
                + ", autoPublish=" + autoPublish
                + ", model=" + model
                + ", apiKey=<redacted>]";
    }
}