package com.prompthub.admin.settlement.controller;

import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.dto.response.SettlementDeliveryListResponse;
import com.prompthub.admin.settlement.dto.response.SettlementDeliverySummaryResponse;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import com.prompthub.admin.settlement.service.SettlementDeliveryService;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("${api.init}/admin/settlements/deliveries")
@RequiredArgsConstructor
@Tag(
        name = "Admin Settlement Delivery",
        description = "어드민 정산 전달·대사 관리 API")
@Validated
public class SettlementDeliveryController {

    private final SettlementDeliveryService service;

    @GetMapping
    @Operation(
            summary = "정산 전달 목록 조회",
            description = "정산 전달 상태를 필터링하고 정산 또는 전달 요청 ID로 조회합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = @Content(schema = @Schema(
                        implementation = SettlementDeliveryListResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "요청 값 오류",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "인증 정보 없음",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 없음",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementDeliveryListResponse> getList(
            @Parameter(description = "전달 상태 필터")
            @RequestParam(required = false)
            SettlementDeliveryStatus status,
            @Parameter(description = "전달 실패·불일치만 조회")
            @RequestParam(defaultValue = "false")
            boolean problemOnly,
            @Parameter(
                    description = "정산 ID 또는 전달 요청 ID(UUID, 정확히 일치)")
            @RequestParam(required = false)
            UUID identifier,
            @Parameter(description = "0-base 페이지 번호")
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,
            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(100)
            int size) {
        return ApiResult.success(service.getList(
                new SettlementDeliveryListQuery(
                        status,
                        problemOnly,
                        identifier,
                        page,
                        size)));
    }

    @GetMapping("/summary")
    @Operation(
            summary = "정산 전달 상태 요약 조회",
            description = "전달 상태별 건수와 수동 재전송 실행 중 건수를 조회합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = @Content(schema = @Schema(
                        implementation = SettlementDeliverySummaryResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "인증 정보 없음",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 없음",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementDeliverySummaryResponse> getSummary() {
        return ApiResult.success(service.getSummary());
    }

    @PostMapping("/{settlementDeliveryId}/retry")
    @Operation(
            summary = "정산 전달 수동 재전송 요청",
            description = "전달 실패 건 하나를 처리하는 단발성 Settlement Service Job을 요청합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "재전송 요청 접수"),
        @ApiResponse(
                responseCode = "401",
                description = "인증 정보 없음",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 없음",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "정산 전달 건 없음",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "재전송 불가 상태 또는 이미 실행 중",
                content = @Content(schema = @Schema(
                        implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApiResult<Void>> retry(
            @Parameter(description = "정산 전달 레코드 ID(UUID)")
            @PathVariable
            UUID settlementDeliveryId,
            @Parameter(
                    description = "요청 수행자 ID(UUID)",
                    in = ParameterIn.HEADER)
            @RequestHeader("X-User-Id")
            UUID actorId) {
        log.info(
                "정산 전달 수동 재전송 요청. settlementDeliveryId={}, actorId={}",
                settlementDeliveryId,
                actorId);
        service.retry(settlementDeliveryId);
        return ResponseEntity.accepted().body(ApiResult.success());
    }
}
