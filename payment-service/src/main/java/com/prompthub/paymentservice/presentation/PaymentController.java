package com.prompthub.paymentservice.presentation;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.command.RefundPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.paymentservice.application.usecase.RefundPaymentUseCase;
import com.prompthub.paymentservice.presentation.config.SwaggerConfig;
import com.prompthub.paymentservice.presentation.dto.request.ConfirmPaymentRequest;
import com.prompthub.paymentservice.presentation.dto.response.ConfirmPaymentResponse;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 승인 및 환불 API")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ConfirmPaymentUseCase confirmPaymentUseCase;
    private final RefundPaymentUseCase refundPaymentUseCase;

    @Operation(summary = "결제 승인",
        description = "토스페이먼츠 SDK에서 전달받은 paymentKey로 최종 결제를 승인합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "결제 승인 완료",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = SwaggerConfig.ConfirmPaymentApiResult.class),
                examples = @ExampleObject(value = """
                    {
                      "success": true,
                      "data": { "paymentId": "550e8400-e29b-41d4-a716-446655440000" },
                      "message": "success"
                    }
                    """))),
        @ApiResponse(responseCode = "400", description = "입력값 오류(V001) 또는 PG사 결제 실패(PAY_FAILED)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "입력값 오류", value = """
                        {
                          "success": false,
                          "data": null,
                          "message": "paymentKey: 공백일 수 없습니다",
                          "code": "V001"
                        }
                        """),
                    @ExampleObject(name = "PG사 결제 실패", value = """
                        {
                          "success": false,
                          "data": null,
                          "message": "PG사 결제가 실패했습니다.",
                          "code": "PAY_FAILED"
                        }
                        """)
                })),
        @ApiResponse(responseCode = "409", description = "이미 결제된 주문(PAY002)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "이미 결제된 주문입니다.",
                      "code": "PAY002"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "BUYER 역할 없음(PAY007) 또는 본인 주문 아님(PAY010)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "BUYER 역할 없음", value = """
                        {
                          "success": false,
                          "data": null,
                          "message": "결제/환불 권한이 없습니다.",
                          "code": "PAY007"
                        }
                        """),
                    @ExampleObject(name = "본인 주문 아님", value = """
                        {
                          "success": false,
                          "data": null,
                          "message": "본인 주문만 결제할 수 있습니다.",
                          "code": "PAY010"
                        }
                        """)
                })),
        @ApiResponse(responseCode = "404", description = "주문 정보 없음(PAY008)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "주문 정보를 찾을 수 없습니다.",
                      "code": "PAY008"
                    }
                    """))),
        @ApiResponse(responseCode = "503", description = "주문 정보 확보 불가(PAY009)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "주문 정보를 확보할 수 없습니다.",
                      "code": "PAY009"
                    }
                    """))),
        @ApiResponse(responseCode = "502", description = "PG사 처리 오류(PAY003)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "PG사 처리 중 오류가 발생했습니다.",
                      "code": "PAY003"
                    }
                    """)))
    })
    @PostMapping("/confirm")
    public ResponseEntity<ApiResult<ConfirmPaymentResponse>> confirm(
        @Parameter(description = "사용자 UUID (Gateway 주입)", required = true,
            example = "550e8400-e29b-41d4-a716-446655440000")
        @RequestHeader("X-User-Id") UUID userId,
        @Parameter(description = "사용자 역할 목록 (Gateway 주입, 쉼표 구분)", required = true,
            example = "BUYER,SELLER")
        @RequestHeader("X-User-Role") String userRoles,
        @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        if (Arrays.stream(userRoles.split(",")).noneMatch("BUYER"::equals)) {
            throw new BusinessException(PaymentErrorCode.INSUFFICIENT_ROLE);
        }
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
            request.paymentKey(), request.orderId(), userId
        );
        PaymentResult result = confirmPaymentUseCase.confirm(command);
        return ResponseEntity.ok(ApiResult.success(new ConfirmPaymentResponse(result.paymentId())));
    }

    @Operation(summary = "환불 요청",
        description = "PAID 상태 결제 건의 전체 환불을 요청합니다. 응답 202는 요청 접수를 의미하며, 실제 환불은 비동기로 처리됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "환불 요청 접수",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "success": true,
                      "data": null,
                      "message": "success"
                    }
                    """))),
        @ApiResponse(responseCode = "400", description = "환불 불가 상태(PAY004)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "환불 가능한 상태가 아닙니다.",
                      "code": "PAY004"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "BUYER 역할 없음(PAY007)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "BUYER 역할 없음", value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "결제/환불 권한이 없습니다.",
                      "code": "PAY007"
                    }
                    """))),
        @ApiResponse(responseCode = "403", description = "본인 결제 건 아님(PAY006)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "본인 결제 건 아님", value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "본인 결제 건만 환불할 수 있습니다.",
                      "code": "PAY006"
                    }
                    """))),
        @ApiResponse(responseCode = "404", description = "결제 건 없음(PAY005)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "결제 건을 찾을 수 없습니다.",
                      "code": "PAY005"
                    }
                    """)))
    })
    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<ApiResult<Void>> refund(
        @Parameter(description = "사용자 UUID (Gateway 주입)", required = true)
        @RequestHeader("X-User-Id") UUID userId,
        @Parameter(description = "사용자 역할 목록 (Gateway 주입, 쉼표 구분)", required = true,
            example = "BUYER,SELLER")
        @RequestHeader("X-User-Role") String userRoles,
        @Parameter(description = "환불할 Payment ID", required = true,
            example = "550e8400-e29b-41d4-a716-446655440000")
        @PathVariable UUID paymentId
    ) {
        if (Arrays.stream(userRoles.split(",")).noneMatch("BUYER"::equals)) {
            throw new BusinessException(PaymentErrorCode.INSUFFICIENT_ROLE);
        }
        refundPaymentUseCase.refund(new RefundPaymentCommand(paymentId, userId));
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(ApiResult.success(null));
    }
}
