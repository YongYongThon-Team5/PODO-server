package com.podo.server.quiz.web;

import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import com.podo.server.quiz.service.QuizService;
import com.podo.server.visit.input.VisitInput;
import com.podo.server.visit.web.VisitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(
        value = "/api/visits/{visitId}",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
@Tag(name = "퀴즈")
@SecurityRequirement(name = "BearerAuth")
@ApiResponses({
        @ApiResponse(
                responseCode = "400",
                description = "입력 오류",
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
                description = "본인 방문·현재 문항이 아님",
                content = @Content(
                        schema = @Schema(implementation = ErrorResponse.class)
                )
        ),
        @ApiResponse(
                responseCode = "409",
                description = "상태·퀴즈 준비·콘텐츠 정정·장소 이용 불가",
                content = @Content(
                        schema = @Schema(implementation = ErrorResponse.class)
                )
        )
})
public class QuizController {
    private final QuizService service;

    @Schema(
            name = "SubmitQuizAnswer",
            requiredProperties = "answer",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record AnswerBody(
            @Schema(allowableValues = {"O", "X"})
            String answer
    ) {}

    @Operation(
            operationId = "reportVisit",
            summary = "방문 신고와 준비된 퀴즈 시작",
            description = """
                    AI 호출 없음. 준비된 세트만 연결한다.
                    재전송은 현재 방문 반환.
                    위치 인증을 수행하지 않는다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "방문 현재 상태",
            useReturnTypeSchema = true
    )
    @PostMapping("/report")
    public ResponseEntity<VisitResponse> report(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            HttpServletRequest request
    ) throws IOException {
        empty(request);

        return response(
                service.report(account(actor), VisitInput.uuid(visitId))
        );
    }

    @Operation(
            operationId = "getQuiz",
            summary = "현재 퀴즈와 진행 조회",
            description = """
                    미응답 문항의 정답·해설과
                    미열람 힌트 본문은 제공하지 않는다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "퀴즈 진행",
            useReturnTypeSchema = true
    )
    @GetMapping("/quiz")
    public ResponseEntity<QuizResponses.Progress> get(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            HttpServletRequest request
    ) throws IOException {
        empty(request);

        return response(
                service.get(account(actor), VisitInput.uuid(visitId))
        );
    }

    @Operation(
            operationId = "submitAnswer",
            summary = "OX 답안 제출·채점",
            description = """
                    첫 답안 불변.
                    서로 다른 3문항 응답과 방문 완료를 원자 처리한다.
                    정답 수는 완료 조건이 아니다.
                    """,
            requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(
                                    implementation = AnswerBody.class
                            )
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "저장된 채점 결과",
            useReturnTypeSchema = true
    )
    @PutMapping(
            value = "/answers/{questionId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<QuizResponses.AnswerResult> answer(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            @PathVariable String questionId,
            @Parameter(hidden = true) @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        noQuery(request);

        if (body == null
                || !body.isObject()
                || !body.propertyNames().equals(Set.of("answer"))
                || !body.get("answer").isString()
                || !Set.of("O", "X").contains(body.get("answer").asText())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return response(
                service.answer(
                        account(actor),
                        VisitInput.uuid(visitId),
                        VisitInput.uuid(questionId),
                        body.get("answer").asText()
                )
        );
    }

    @Operation(
            operationId = "viewHint",
            summary = "힌트 열람·중복 없는 기록",
            description = """
                    정상 완료 후에는 기존에 열람한 단계만
                    다시 조회할 수 있다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "힌트 본문",
            useReturnTypeSchema = true
    )
    @PutMapping("/hint-views/{questionId}/{step}")
    public ResponseEntity<QuizResponses.HintResult> hint(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            @PathVariable String questionId,
            @Parameter(
                    schema = @Schema(
                            type = "integer",
                            allowableValues = {"1", "2"}
                    )
            )
            @PathVariable String step,
            HttpServletRequest request
    ) throws IOException {
        empty(request);

        if (!Set.of("1", "2").contains(step)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return response(
                service.hint(
                        account(actor),
                        VisitInput.uuid(visitId),
                        VisitInput.uuid(questionId),
                        Integer.parseInt(step)
                )
        );
    }

    @Operation(
            operationId = "restartQuiz",
            summary = "오류 세트를 정정된 버전으로 교체",
            description = """
                    같은 장소·같은 관찰 범위의 상위 공개 버전만 연결한다.
                    과거 답안·힌트·최초 시작 시각을 보존한다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "현재 퀴즈 진행",
            useReturnTypeSchema = true
    )
    @PostMapping("/quiz/restart")
    public ResponseEntity<QuizResponses.Progress> restart(
            @AuthenticationPrincipal AuthenticatedAccount actor,
            @PathVariable String visitId,
            HttpServletRequest request
    ) throws IOException {
        empty(request);

        return response(
                service.restart(account(actor), VisitInput.uuid(visitId))
        );
    }

    private void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private void empty(HttpServletRequest request) throws IOException {
        noQuery(request);

        if (request.getContentLengthLong() > 0
                || request.getInputStream().read() != -1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private UUID account(AuthenticatedAccount actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        return actor.accountId();
    }

    private <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(body);
    }
}