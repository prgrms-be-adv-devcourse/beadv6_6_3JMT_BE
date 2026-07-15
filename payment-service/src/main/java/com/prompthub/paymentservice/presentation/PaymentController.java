package com.prompthub.paymentservice.presentation;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
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
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 승인 API")
@RestController
@RequestMapping("/api/v2/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ConfirmPaymentUseCase confirmPaymentUseCase;

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
        @ApiResponse(responseCode = "403", description = "본인 주문 아님(PAY010)",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "success": false,
                      "data": null,
                      "message": "본인 주문만 결제할 수 있습니다.",
                      "code": "PAY010"
                    }
                    """))),
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
        @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
            request.paymentKey(), request.orderId(), userId
        );
        PaymentResult result = confirmPaymentUseCase.confirm(command);
        return ResponseEntity.ok(ApiResult.success(new ConfirmPaymentResponse(result.paymentId())));
    }
}
