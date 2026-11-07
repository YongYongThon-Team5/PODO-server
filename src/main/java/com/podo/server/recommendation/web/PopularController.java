package com.podo.server.recommendation.web;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.recommendation.service.PopularService;
import com.podo.server.recommendation.web.PopularResponses.Result;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "추천과 인기")
public class PopularController {
    private final PopularService service;

    public PopularController(PopularService service) {
        this.service = service;
    }

    @Operation(summary = "전체 인기 상위 15곳",
            description = "방문 신고(reportedAt)가 있는 방문을 공개 장소별 서로 다른 계정 수로 집계한다. "
                    + "계정 수 DESC, 이름 COLLATE C ASC, id ASC로 최대 15곳. 기록이 없으면 items=[]와 COLLECTING.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인기 장소", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "정의되지 않은 쿼리 또는 본문",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "전달한 Bearer가 유효하지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/places/popular")
    public ResponseEntity<Result> popular(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1
                || !request.getParameterMap().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Request-Id", UUID.randomUUID().toString()).body(service.popular());
    }
}
