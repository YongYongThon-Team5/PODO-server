package com.podo.server.observation.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ObservationData {

    private ObservationData() {
    }

    public record Row(
            UUID id,
            UUID visitId,
            String status,
            int attemptCount,
            String failureCode,
            String requestHash,
            UUID leaseToken,
            Instant leaseExpiresAt,
            Instant createdAt
    ) {
    }

    public record Image(
            UUID id,
            int position,
            String storageKey,
            String mimeType,
            long sizeBytes
    ) {
    }

    public record Lease(
            UUID observationId,
            UUID token
    ) {
    }

    @Schema(name = "ObservationImage", requiredProperties = {"id", "position", "contentUrl"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ImageView(
            UUID id,
            @Schema(minimum = "1", maximum = "3")
            int position,
            @Schema(description = "소유자 Bearer 인증으로 조회할 사진 경로")
            String contentUrl
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "ObservationState",
            requiredProperties = {"id", "visitId", "generationStatus", "attemptCount", "failureCode",
                    "retryAvailable", "startAvailable", "introduction", "images"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record State(
            UUID id,
            UUID visitId,
            @Schema(allowableValues = {"UPLOADED", "GENERATING", "READY", "FAILED"},
                    description = "READY는 생성 완료이며 공개·시작 가능 여부는 startAvailable로 확인")
            String generationStatus,
            @Schema(minimum = "0", maximum = "3", description = "작업자가 생성 작업을 점유한 횟수")
            int attemptCount,
            @Schema(types = {"string", "null"}, description = "실패 사유 코드, 실패 전에는 null")
            String failureCode,
            @Schema(description = "최신 FAILED 작업이며 재시도 가능한 실패·시도 한도·방문 상태를 만족")
            boolean retryAvailable,
            @Schema(description = "공개된 최신 READY 작업이고 방문이 PLANNED이며 장소가 공개 상태")
            boolean startAvailable,
            @Schema(types = {"string", "null"}, description = "READY 작업의 관찰 안내, 생성 전에는 null")
            String introduction,
            @ArraySchema(minItems = 1, maxItems = 3)
            List<ImageView> images
    ) {
    }

    public record Content(
            byte[] bytes,
            String mimeType
    ) {
    }
}
