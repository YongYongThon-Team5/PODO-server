package com.podo.server.recommendation.web;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.recommendation.service.RecommendationOptionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "선택 목록")
public class RecommendationOptionsController {
    private final RecommendationOptionsService service;

    public RecommendationOptionsController(RecommendationOptionsService service) {
        this.service = service;
    }

    @Operation(summary = "목적·지역·실내외 선호 목록",
            description = "단일 선택할 목적 코드, 지역 코드와 실내외 선호 INDOOR/OUTDOOR/ANY를 반환한다. 새 요청에는 활성 코드만 허용한다. 실내외는 순위 선호이며 강제 제외 필터가 아니다.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추천 선택 목록"),
            @ApiResponse(responseCode = "400", description = "정의되지 않은 쿼리 또는 본문",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "전달한 Bearer가 유효하지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "OPTIONS_NOT_CONFIGURED: 목적 운영 목록 미설정",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/recommendation-options")
    public ResponseEntity<RecommendationOptionsResponse> getOptions(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1
                || !request.getParameterMap().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Request-Id", UUID.randomUUID().toString()).body(service.getOptions());
    }
}
