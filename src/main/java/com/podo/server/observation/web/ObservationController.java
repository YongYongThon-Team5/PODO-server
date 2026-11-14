package com.podo.server.observation.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.observation.domain.ObservationData.State;
import com.podo.server.observation.service.ObservationService;
import com.podo.server.profile.image.processing.ProfileImageProcessor;
import com.podo.server.visit.input.VisitInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/visits/{visitId}/observations")
@Tag(name = "관찰 사진")
@SecurityRequirement(name = "BearerAuth")
public class ObservationController {

    private final ObservationService service;

    public record RetryBody(
            @NotNull
            @Min(1)
            @Max(3)
            @Schema(minimum = "1", maximum = "3", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "마지막 상태 조회의 attemptCount. 같은 실패 재전송에는 같은 값을 사용")
            Integer expectedAttempt
    ) {
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(
            operationId = "uploadObservation",
            summary = "관찰 사진 1~3장 업로드 및 생성 예약",
            description = """
                    multipart files 1~3장, 각 최대 5MiB.
                    같은 파일 묶음 재전송에는 같은 Idempotency-Key를 사용한다.
                    생성은 비동기이며, 상태 조회의 startAvailable을 확인한 후 기존 report API로 시작한다.
                    """,
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
                    required = true, schema = @Schema(type = "string", minLength = 8, maxLength = 128))
    )
    @ApiResponse(responseCode = "202", description = "업로드 접수 및 현재 생성 상태", useReturnTypeSchema = true)
    public ResponseEntity<State> upload(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String key,
            MultipartHttpServletRequest request
    ) throws IOException {
        UUID account = account(actor);

        noQuery(request);

        var map = request.getMultiFileMap();
        var files = map.getOrDefault("files", List.of());

        if (map.size() != 1
                || files.isEmpty()
                || files.size() > 3
                || !request.getParameterMap().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }

        var uploads = new java.util.ArrayList<
                ObservationService.UploadFile
                >();

        for (var file : files) {
            if (file.getSize() > ProfileImageProcessor.MAX_BYTES) {
                throw new BusinessException(
                        ErrorCode.UPLOAD_TOO_LARGE
                );
            }

            uploads.add(new ObservationService.UploadFile(
                    file.getBytes(),
                    file.getContentType()
            ));
        }

        return response(
                202,
                service.upload(
                        account,
                        VisitInput.uuid(visitId),
                        key,
                        uploads
                )
        );
    }

    @GetMapping("/latest")
    @Operation(
            operationId = "getLatestObservation",
            summary = "방문의 최신 관찰 생성 상태 조회"
    )
    public ResponseEntity<State> latest(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            HttpServletRequest request
    ) {
        noQuery(request);

        return response(
                200,
                service.latest(
                        account(actor),
                        VisitInput.uuid(visitId)
                )
        );
    }

    @GetMapping("/{observationId}")
    @Operation(
            operationId = "getObservation",
            summary = "본인 관찰 생성 상태 조회"
    )
    public ResponseEntity<State> get(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            @PathVariable String observationId,
            HttpServletRequest request
    ) {
        noQuery(request);

        return response(
                200,
                service.get(
                        account(actor),
                        VisitInput.uuid(visitId),
                        VisitInput.uuid(observationId)
                )
        );
    }

    @PostMapping(
            value = "/{observationId}/retry",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            operationId = "retryObservation",
            summary = "실패한 생성 작업을 현재 시도 번호로 재요청",
            description = """
                    retryAvailable=true일 때 조회한 attemptCount를 expectedAttempt로 전송한다.
                    같은 시도 번호의 재전송은 새 작업을 중복 예약하지 않는다.
                    지연된 이전 시도 요청은 현재 상태를 반환하며 더 최신 실패를 재시도하지 않는다.
                    """
    )
    @ApiResponse(responseCode = "202", description = "재시도 접수 또는 이미 진행된 요청의 현재 상태", useReturnTypeSchema = true)
    public ResponseEntity<State> retry(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            @PathVariable String observationId,
            @Valid @RequestBody RetryBody body,
            HttpServletRequest request
    ) {
        noQuery(request);

        return response(
                202,
                service.retry(
                        account(actor),
                        VisitInput.uuid(visitId),
                        VisitInput.uuid(observationId),
                        body.expectedAttempt()
                )
        );
    }

    @GetMapping("/{observationId}/images/{imageId}")
    @Operation(
            operationId = "readObservationImage",
            summary = "본인 관찰 사진 조회"
    )
    public ResponseEntity<byte[]> image(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            @PathVariable String observationId,
            @PathVariable String imageId,
            HttpServletRequest request
    ) {
        noQuery(request);

        var content = service.image(
                account(actor),
                VisitInput.uuid(visitId),
                VisitInput.uuid(observationId),
                VisitInput.uuid(imageId)
        );

        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.noStore().cachePrivate()
                )
                .header(
                        "X-Request-Id",
                        UUID.randomUUID().toString()
                )
                .contentType(
                        MediaType.parseMediaType(content.mimeType())
                )
                .contentLength(content.bytes().length)
                .body(content.bytes());
    }

    private void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null
                && !request.getQueryString().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }
    }

    private UUID account(AuthenticatedAccount actor) {
        if (actor == null) {
            throw new BusinessException(
                    ErrorCode.AUTHENTICATION_REQUIRED
            );
        }

        return actor.accountId();
    }

    private <T> ResponseEntity<T> response(
            int status,
            T body
    ) {
        return ResponseEntity.status(status)
                .cacheControl(
                        CacheControl.noStore().cachePrivate()
                )
                .header(
                        "X-Request-Id",
                        UUID.randomUUID().toString()
                )
                .body(body);
    }
}
