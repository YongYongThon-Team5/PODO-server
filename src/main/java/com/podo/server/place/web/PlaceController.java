package com.podo.server.place.web;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.CursorResponse;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.place.service.PlaceService;
import com.podo.server.place.service.PlaceService.Bbox;
import com.podo.server.place.web.PlaceResponses.PlaceDetail;
import com.podo.server.place.web.PlaceResponses.PlaceSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "장소")
@SecurityRequirements
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "입력·날짜·커서 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "전달한 Bearer가 유효하지 않음", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class PlaceController {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final PlaceService service;

    public PlaceController(PlaceService service) {
        this.service = service;
    }

    @Operation(summary = "공개 장소 목록·지도 조회",
            description = "PUBLISHED 장소만 name ASC, id ASC로 조회한다. bbox 또는 지역 필터를 바꾸면 cursor를 초기화한다.",
            parameters = {
                    @Parameter(name = "bbox", in = ParameterIn.QUERY, description = "minLongitude,minLatitude,maxLongitude,maxLatitude"),
                    @Parameter(name = "districtCode", in = ParameterIn.QUERY, description = "선택 지역 코드 (CHEOIN, GIHEUNG, SUJI)"),
                    @Parameter(name = "cursor", in = ParameterIn.QUERY),
                    @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            })
    @ApiResponse(responseCode = "200", description = "공개 장소 목록", useReturnTypeSchema = true)
    @GetMapping("/api/places")
    public ResponseEntity<CursorResponse<PlaceSummary>> list(HttpServletRequest request) throws IOException {
        requireOnly(request, Set.of("bbox", "districtCode", "cursor", "limit"));
        String districtCode = request.getParameter("districtCode");
        if (districtCode != null && (districtCode.isEmpty() || districtCode.length() > 64)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String cursor = request.getParameter("cursor");
        if (cursor != null && cursor.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }
        return ok(service.list(districtCode, parseBbox(request.getParameter("bbox")), cursor,
                parseLimit(request.getParameter("limit"))));
    }

    @Operation(summary = "공개 장소 상세",
            description = "상세 조회만으로 방문을 만들지 않는다. 비공개 장소는 404. visitDate가 없으면 weather=null, 조회 실패면 UNKNOWN.",
            parameters = @Parameter(name = "visitDate", in = ParameterIn.QUERY, description = "YYYY-MM-DD",
                    schema = @Schema(type = "string", format = "date")))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 상세", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "없거나 비공개인 장소", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/places/{placeId}")
    public ResponseEntity<PlaceDetail> detail(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String placeId,
            HttpServletRequest request) throws IOException {
        requireOnly(request, Set.of("visitDate"));
        UUID id;
        try {
            id = UUID.fromString(placeId);
            if (!id.toString().equalsIgnoreCase(placeId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String rawDate = request.getParameter("visitDate");
        LocalDate visitDate = null;
        if (rawDate != null) {
            try {
                visitDate = LocalDate.parse(rawDate);
            } catch (DateTimeParseException ex) {
                throw new BusinessException(ErrorCode.INVALID_DATE);
            }
        }
        return ok(service.detail(id, visitDate));
    }

    private void requireOnly(HttpServletRequest request, Set<String> allowed) throws IOException {
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1
                || !allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private int parseLimit(String raw) {
        if (raw == null) return 20;
        try {
            int limit = Integer.parseInt(raw);
            if (limit < 1 || limit > 100) throw new NumberFormatException();
            return limit;
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private Bbox parseBbox(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(",", -1);
        if (parts.length != 4) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!NUMBER.matcher(parts[i]).matches()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
            v[i] = Double.parseDouble(parts[i]);
        }
        boolean valid = v[0] >= -180 && v[2] <= 180 && v[1] >= -90 && v[3] <= 90 && v[0] < v[2] && v[1] < v[3];
        if (!valid) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return new Bbox(v[0], v[1], v[2], v[3], raw);
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Request-Id", UUID.randomUUID().toString()).body(body);
    }
}
