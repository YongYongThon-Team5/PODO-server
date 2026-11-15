package com.podo.server.visit.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.CursorResponse;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.visit.input.VisitFilter;
import com.podo.server.visit.input.VisitInput;
import com.podo.server.visit.service.VisitService;
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
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(
        value = "/api/visits",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
@Tag(name = "방문")
@SecurityRequirement(name = "BearerAuth")
@ApiResponses({
        @ApiResponse(
                responseCode = "400",
                description = "입력·날짜·커서 오류",
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
        ),
        @ApiResponse(
                responseCode = "404",
                description = "없거나 본인 소유가 아닌 리소스",
                content = @Content(
                        schema = @Schema(implementation = ErrorResponse.class)
                )
        ),
        @ApiResponse(
                responseCode = "409",
                description = "활성 방문·요청 키·상태·장소 이용 불가",
                content = @Content(
                        schema = @Schema(implementation = ErrorResponse.class)
                )
        )
})
public class VisitController {
    private final VisitService service;

    @Operation(
            operationId = "createVisit",
            summary = "방문 예정 등록",
            description = """
                    계정당 활성 방문 1건.
                    최초 등록 201, 동일 키·동일 요청 재전송 200.
                    아이 선택은 필수가 아니다.
                    """,
            parameters = @Parameter(
                    name = "Idempotency-Key",
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(
                            type = "string",
                            minLength = 8,
                            maxLength = 128
                    )
            ),
            requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(
                                    implementation = VisitSchemas.Create.class
                            )
                    )
            )
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "생성",
                    useReturnTypeSchema = true
            ),
            @ApiResponse(
                    responseCode = "200",
                    description = "같은 요청 재전송",
                    useReturnTypeSchema = true
            )
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VisitResponse> create(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @Parameter(hidden = true) @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        requireQuery(request, Set.of());

        var keys = Collections.list(request.getHeaders("Idempotency-Key"));

        if (keys.size() != 1) {
            throw invalid();
        }

        var result = service.create(
                accountId(actor),
                VisitInput.requestKey(keys.getFirst()),
                VisitInput.create(body)
        );

        return response(result.created() ? 201 : 200, result.visit());
    }

    @Operation(
            operationId = "listVisits",
            summary = "내 방문 목록",
            description = """
                    기본 정렬은 createdAt DESC, id DESC.
                    status=COMPLETED&sort=COMPLETED_AT_DESC이면
                    completedAt DESC, id DESC로 조회한다.
                    필터나 정렬을 바꾸면 커서 없이 새 조회한다.
                    기존 생성일순 커서는 그대로 사용할 수 있다.
                    """,
            parameters = {
                    @Parameter(
                            name = "status",
                            in = ParameterIn.QUERY,
                            description = """
                                    쉼표 구분:
                                    PLANNED,QUIZ_IN_PROGRESS,COMPLETED,CANCELLED
                                    """
                    ),
                    @Parameter(
                            name = "placeId",
                            in = ParameterIn.QUERY,
                            schema = @Schema(type = "string", format = "uuid")
                    ),
                    @Parameter(name = "cursor", in = ParameterIn.QUERY),
                    @Parameter(
                            name = "sort",
                            in = ParameterIn.QUERY,
                            description = "완료일순은 status=COMPLETED 단독 필터에서만 사용",
                            schema = @Schema(
                                    type = "string",
                                    allowableValues = {"CREATED_AT_DESC", "COMPLETED_AT_DESC"},
                                    defaultValue = "CREATED_AT_DESC"
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
    @ApiResponse(
            responseCode = "200",
            description = "본인 방문 목록",
            useReturnTypeSchema = true
    )
    @GetMapping
    public ResponseEntity<CursorResponse<VisitResponse>> list(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            HttpServletRequest request
    ) throws IOException {
        requireQuery(request, Set.of("status", "placeId", "cursor", "limit", "sort"));
        requireNoBody(request);

        var filter = VisitFilter.parse(
                request.getParameter("status"),
                request.getParameter("placeId"),
                request.getParameter("limit"),
                request.getParameter("sort")
        );

        return response(
                200,
                service.list(accountId(actor), filter, request.getParameter("cursor"))
        );
    }

    @Operation(
            operationId = "getVisit",
            summary = "내 방문 상세·상태 조회"
    )
    @ApiResponse(
            responseCode = "200",
            description = "방문 상세",
            useReturnTypeSchema = true
    )
    @GetMapping("/{visitId}")
    public ResponseEntity<VisitResponse> get(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @Parameter(schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String visitId,
            HttpServletRequest request
    ) throws IOException {
        requireQuery(request, Set.of());
        requireNoBody(request);

        return response(
                200,
                service.get(accountId(actor), VisitInput.uuid(visitId))
        );
    }

    @Operation(
            operationId = "changeVisitDate",
            summary = "방문 예정일 변경",
            description = "PLANNED에서 날짜만 변경한다.",
            requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(
                                    implementation = VisitSchemas.Patch.class
                            )
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "예정일 변경",
            useReturnTypeSchema = true
    )
    @PatchMapping(
            value = "/{visitId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<VisitResponse> patch(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @Parameter(schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String visitId,
            @Parameter(hidden = true) @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        requireQuery(request, Set.of());

        return response(
                200,
                service.changeDate(
                        accountId(actor),
                        VisitInput.uuid(visitId),
                        VisitInput.patchDate(body)
                )
        );
    }

    @Operation(
            operationId = "cancelVisit",
            summary = "방문 예정 취소",
            description = """
                    PLANNED에서 취소.
                    이미 CANCELLED이면 기존 결과 반환.
                    요청 본문 없음.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "취소 결과",
            useReturnTypeSchema = true
    )
    @PostMapping("/{visitId}/cancel")
    public ResponseEntity<VisitResponse> cancel(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @Parameter(schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String visitId,
            HttpServletRequest request
    ) throws IOException {
        requireQuery(request, Set.of());
        requireNoBody(request);

        return response(
                200,
                service.cancel(accountId(actor), VisitInput.uuid(visitId))
        );
    }

    private void requireQuery(
            HttpServletRequest request,
            Set<String> allowed
    ) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream()
                .anyMatch(values -> values.length != 1)) {
            throw invalid();
        }
    }

    private void requireNoBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 0
                || request.getInputStream().read() != -1) {
            throw invalid();
        }
    }

    private UUID accountId(AuthenticatedAccount actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        return actor.accountId();
    }

    private <T> ResponseEntity<T> response(int status, T body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(body);
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
