package com.podo.server.observation.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.observation.domain.ObservationData.State;
import com.podo.server.observation.service.ObservationService;
import com.podo.server.profile.image.processing.ProfileImageProcessor;
import com.podo.server.visit.input.VisitInput;
import io.swagger.v3.oas.annotations.Operation;
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
            Integer expectedAttempt
    ) {
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(
            operationId = "uploadObservation",
            summary = "관찰 사진 1~3장 업로드 및 생성 예약"
    )
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
            summary = "실패한 생성 작업을 현재 시도 번호로 재요청"
    )
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