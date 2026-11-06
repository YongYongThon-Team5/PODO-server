package com.podo.server.visit.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.visit.service.CompletedPlaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        value = "/api/me/completed-places",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
@Tag(name = "완료 지도")
@SecurityRequirement(name = "BearerAuth")
public class CompletedPlaceController {
    private final CompletedPlaceService service;

    @Operation(
            operationId = "getCompletedPlaces",
            summary = "가족 공통 완료 지도",
            description = """
                    계정의 COMPLETED 방문을 장소별로 묶는다.
                    lastCompletedAt DESC, placeId DESC.
                    summary는 계정 전체 완료 장소 집계다.
                    아이 색상과 장소 정보는 현재 값을 사용한다.
                    장소 비공개·퀴즈 정정 후에도 완료 기록을 유지한다.
                    asOf 이후 완료 기록은 다음 새 조회에서 반영한다.
                    비공개 장소에 좌표가 없으면 null이며 프론트는 마커 표시를 생략한다.
                    """,
            parameters = {
                    @Parameter(
                            name = "cursor",
                            in = ParameterIn.QUERY,
                            description = "이전 응답의 nextCursor. 최초 조회에서는 생략한다.",
                            schema = @Schema(
                                    type = "string",
                                    maxLength = 512
                            )
                    ),
                    @Parameter(
                            name = "limit",
                            in = ParameterIn.QUERY,
                            schema = @Schema(
                                    type = "integer",
                                    minimum = "1",
                                    maximum = "100",
                                    defaultValue = "20"
                            )
                    )
            }
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "완료 장소 목록",
                    useReturnTypeSchema = true
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "입력 또는 커서 오류",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @GetMapping
    public ResponseEntity<CompletedPlaceResponse.Page> list(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            HttpServletRequest request
    ) throws IOException {
        if (actor == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        if (!Set.of("cursor", "limit")
                .containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream()
                .anyMatch(values -> values.length != 1)
                || request.getContentLengthLong() > 0
                || request.getInputStream().read() != -1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(service.list(
                        actor.accountId(),
                        request.getParameter("limit"),
                        request.getParameter("cursor")
                ));
    }
}