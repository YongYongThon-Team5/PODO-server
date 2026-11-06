package com.podo.server.profile.options;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
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
public class ProfileOptionsController {
    private final ProfileOptionsService service;

    public ProfileOptionsController(ProfileOptionsService service) {
        this.service = service;
    }

    @Operation(summary = "프로필 선택 목록과 사진 규격", description = "비활성 항목도 기존 선택 표시를 위해 반환한다. 필수 운영 목록이 없으면 503이다.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로필 선택 목록과 확정된 정책"),
            @ApiResponse(responseCode = "400", description = "정의되지 않은 쿼리 또는 본문",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "전달한 Bearer가 유효하지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "OPTIONS_NOT_CONFIGURED: 운영 목록 미설정",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/profile-options")
    public ResponseEntity<ProfileOptionsResponse> getOptions(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1
                || !request.getParameterMap().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Request-Id", UUID.randomUUID().toString()).body(service.getOptions());
    }
}
