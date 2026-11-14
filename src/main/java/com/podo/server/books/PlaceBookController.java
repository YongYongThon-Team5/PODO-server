package com.podo.server.books;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "장소")
@SecurityRequirements
public class PlaceBookController {
    private final PlaceBookService service;

    public PlaceBookController(PlaceBookService service) {
        this.service = service;
    }

    @Operation(summary = "장소 관련 도서와 도서관 조회",
            description = "공개 장소의 선정 도서 최대 3권을 용인시 신간도서 API 첫 100행에서 확인한다. "
                    + "서지 4개 필드가 일치하는 제공자 행에서 제목과 도서관 이름만 반환한다. "
                    + "checkedAt은 서버 확인 시각이다. 쿼리와 본문은 허용하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "제공자 조회 결과와 준비 상태·미발견·실패·조회 범위 경고", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 잘못된 UUID·쿼리·본문", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "전달한 Bearer가 유효하지 않음", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 없거나 비공개인 장소", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(value = "/api/places/{placeId}/books", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PlaceBookResponse> get(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String placeId,
            HttpServletRequest request) throws IOException {
        if (!request.getParameterMap().isEmpty() || request.getContentLengthLong() > 0
                || request.getInputStream().read() != -1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        UUID id;
        try {
            id = UUID.fromString(placeId);
            if (!id.toString().equalsIgnoreCase(placeId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Request-Id", UUID.randomUUID().toString()).body(service.get(id));
    }
}
