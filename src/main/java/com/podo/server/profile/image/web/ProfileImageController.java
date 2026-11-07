package com.podo.server.profile.image.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.profile.image.processing.ProfileImageProcessor;
import com.podo.server.profile.image.service.ProfileImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile-images")
@Tag(name = "아이 프로필")
@SecurityRequirement(name = "BearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "사진 저장소 이용 불가", content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
})
public class ProfileImageController {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String IMAGE_WEBP = "image/webp";
    private final ProfileImageService service;
    private final Semaphore uploadSlot = new Semaphore(1);

    @Operation(operationId = "uploadProfileImage", summary = "프로필 사진 업로드",
            description = "JPEG, PNG, WebP를 5 MiB, 16,777,216픽셀, 가로·세로 각각 16,384픽셀 이하로 받아 저장합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = UploadRequest.class),
                    encoding = @Encoding(name = "file", contentType = "image/jpeg, image/png, image/webp"))))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "프로필 사진 생성", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProfileImageCreated.class)),
                    headers = {@Header(name = REQUEST_ID_HEADER, schema = @Schema(type = "string")),
                            @Header(name = HttpHeaders.CACHE_CONTROL, schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "잘못된 멀티파트 요청", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "파일 크기 또는 픽셀 수 한도 초과", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "415", description = "지원하지 않는 사진 형식", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileImageCreated> upload(@AuthenticationPrincipal AuthenticatedAccount actor,
                                                      MultipartHttpServletRequest request) {
        UUID accountId = accountId(actor);
        var files = request.getMultiFileMap();
        List<MultipartFile> fileParts = files.getOrDefault("file", List.of());
        if (files.size() != 1 || fileParts.size() != 1 || !request.getParameterMap().isEmpty()
                || (request.getQueryString() != null && !request.getQueryString().isEmpty())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        MultipartFile file = fileParts.getFirst();
        if (file.getSize() > ProfileImageProcessor.MAX_BYTES) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        if (!uploadSlot.tryAcquire()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            var created = service.upload(accountId, file.getBytes(), file.getContentType());
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .header(REQUEST_ID_HEADER, UUID.randomUUID().toString()).body(created);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } finally {
            uploadSlot.release();
        }
    }

    @Operation(operationId = "readProfileImage", summary = "내 프로필 사진 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "본인 프로필 사진", content = {
                    @Content(mediaType = MediaType.IMAGE_JPEG_VALUE, schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = IMAGE_WEBP, schema = @Schema(type = "string", format = "binary"))
            }, headers = {@Header(name = REQUEST_ID_HEADER, schema = @Schema(type = "string")),
                    @Header(name = HttpHeaders.CACHE_CONTROL, schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "404", description = "사진이 없거나 본인 소유가 아님", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{imageId}")
    public void read(@AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable @io.swagger.v3.oas.annotations.Parameter(schema = @Schema(type = "string", format = "uuid"))
            String imageId, HttpServletResponse response) throws IOException {
        var image = service.read(accountId(actor), parseImageId(imageId));
        try (var stream = image.stream()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(image.contentType());
            response.setContentLengthLong(image.sizeBytes());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(REQUEST_ID_HEADER, UUID.randomUUID().toString());
            stream.transferTo(response.getOutputStream());
        }
    }

    private UUID parseImageId(String imageId) {
        try {
            UUID id = UUID.fromString(imageId);
            if (id.toString().equalsIgnoreCase(imageId)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // Treat malformed IDs the same way as missing images.
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private UUID accountId(AuthenticatedAccount actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return actor.accountId();
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record UploadRequest(
            @Schema(type = "string", format = "binary", requiredMode = Schema.RequiredMode.REQUIRED) String file
    ) { }
}
