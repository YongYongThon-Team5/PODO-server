package com.podo.server.profile.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.CursorResponse;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.profile.input.ChildProfileInput;
import com.podo.server.profile.service.ChildProfileService;
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
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "아이 프로필")
@SecurityRequirement(name = "BearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "입력·선택 코드·커서 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "운영 목록 미설정", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class ChildProfileController {
    private final ChildProfileService service;

    public ChildProfileController(ChildProfileService service) {
        this.service = service;
    }

    @Operation(summary = "아이 프로필 생성", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true, content = @Content(schema = @Schema(implementation = ChildProfileSchemas.Create.class))))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "프로필 생성", content = @Content(schema = @Schema(implementation = ChildProfileResponse.class))),
            @ApiResponse(responseCode = "404", description = "본인 사진이 아니거나 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "COLOR_ALREADY_USED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/api/child-profiles", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChildProfileResponse> create(@AuthenticationPrincipal AuthenticatedAccount actor,
            @Parameter(hidden = true) @RequestBody JsonNode body, HttpServletRequest request) {
        requireNoQuery(request);
        return response(201, service.create(accountId(actor), ChildProfileInput.create(body)));
    }

    @Operation(summary = "내 아이 프로필 목록", description = "createdAt ASC, id ASC. 커서는 이전 응답 값을 그대로 전달한다.", parameters = {
            @Parameter(name = "cursor", in = ParameterIn.QUERY),
            @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
    })
    @ApiResponse(responseCode = "200", description = "본인 프로필 목록", useReturnTypeSchema = true)
    @GetMapping("/api/child-profiles")
    public ResponseEntity<CursorResponse<ChildProfileResponse>> list(@AuthenticationPrincipal AuthenticatedAccount actor,
            HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1
                || !Set.of("cursor", "limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String cursor = request.getParameter("cursor");
        String rawLimit = request.getParameter("limit");
        int limit = 20;
        if (rawLimit != null) {
            try { limit = Integer.parseInt(rawLimit); }
            catch (NumberFormatException ex) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
        }
        return response(200, service.list(accountId(actor), cursor, limit));
    }

    @Operation(summary = "아이 프로필 수정", description = "생략은 유지하고, 연령·사진의 null은 해제하며 배열은 전체 교체한다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = ChildProfileSchemas.Patch.class))))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로필 수정", content = @Content(schema = @Schema(implementation = ChildProfileResponse.class))),
            @ApiResponse(responseCode = "404", description = "본인 프로필·사진이 아니거나 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "COLOR_ALREADY_USED", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping(value = "/api/child-profiles/{profileId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChildProfileResponse> patch(@AuthenticationPrincipal AuthenticatedAccount actor,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String profileId,
            @Parameter(hidden = true) @RequestBody JsonNode body, HttpServletRequest request) {
        requireNoQuery(request);
        UUID id;
        try {
            id = UUID.fromString(profileId);
            if (!id.toString().equalsIgnoreCase(profileId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
        return response(200, service.patch(accountId(actor), id, ChildProfileInput.patch(body)));
    }

    private UUID accountId(AuthenticatedAccount actor) {
        if (actor == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        return actor.accountId();
    }

    private void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private <T> ResponseEntity<T> response(int status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Request-Id", UUID.randomUUID().toString()).body(body);
    }
}
