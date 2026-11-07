package com.podo.server.recommendation.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.recommendation.service.RecommendationService;
import com.podo.server.recommendation.web.RecommendationResponses.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeType;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "추천과 인기")
public class RecommendationController {
    private static final Set<String> FIELDS =
            Set.of("visitDate", "purposeCode", "childProfileId", "districtCode", "environmentPreference");
    private static final Set<String> PREFERENCES = Set.of("INDOOR", "OUTDOOR", "ANY");

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @Operation(summary = "맞춤 장소 최대 5곳",
            description = "목적 하나와 방문 날짜가 필수다. childProfileId 누락/null은 프로필 없음. 비로그인으로 프로필 ID를 보내면 401, 타인 프로필은 404. "
                    + "서버가 날씨를 조회해 비 예보면 야외 장소를 제외하고 흐림은 야외 감점한다. 부족하면 실제 후보만 반환한다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = RecommendationResponses.RequestSchema.class))))
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추천 결과", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "입력·날짜·선택 코드 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "프로필 ID를 보냈는데 로그인하지 않았거나 토큰이 무효",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "본인 프로필이 아니거나 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/api/recommendations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result> recommend(@AuthenticationPrincipal AuthenticatedAccount actor,
                                            @Parameter(hidden = true) @RequestBody JsonNode body,
                                            HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (body == null || !body.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        for (Map.Entry<String, JsonNode> entry : body.properties()) {
            if (!FIELDS.contains(entry.getKey())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }

        LocalDate visitDate;
        try {
            visitDate = LocalDate.parse(text(body, "visitDate", true));
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.INVALID_DATE);
        }
        if (visitDate.isBefore(LocalDate.now(com.podo.server.weather.Weather.SEOUL))) {
            throw new BusinessException(ErrorCode.INVALID_DATE);
        }
        String purposeCode = text(body, "purposeCode", true);
        if (purposeCode.isEmpty() || purposeCode.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String rawProfileId = text(body, "childProfileId", false);
        UUID childProfileId = null;
        if (rawProfileId != null) {
            try {
                childProfileId = UUID.fromString(rawProfileId);
                if (!childProfileId.toString().equalsIgnoreCase(rawProfileId)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
        String districtCode = text(body, "districtCode", false);
        if (districtCode != null && (districtCode.isEmpty() || districtCode.length() > 64)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String preference = text(body, "environmentPreference", false);
        if (preference != null && !PREFERENCES.contains(preference)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String requestId = UUID.randomUUID().toString();
        UUID accountId = actor == null ? null : actor.accountId();
        Result result = service.recommend(accountId,
                new RecommendationService.Request(visitDate, purposeCode, childProfileId, districtCode, preference),
                requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Request-Id", requestId).body(result);
    }

    private static String text(JsonNode body, String field, boolean required) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return null;
        }
        if (node.getNodeType() != JsonNodeType.STRING) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return node.asString();
    }
}
