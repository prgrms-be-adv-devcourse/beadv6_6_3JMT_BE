package com.prompthub.settlement.presentation.controller;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.usecase.SettlementUseCase;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/admin/settlements")
@RequiredArgsConstructor
@Tag(name = "Settlement", description = "정산 조회 API(관리자)")
public class SettlementController {

    private final SettlementUseCase settlementUseCase;

    @GetMapping("/summary")
    @Operation(summary = "정산 요약 카드 조회",
            description = "정산 관리 화면 상단 요약 카드(상태별 합계·건수)를 조회합니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SettlementSummaryResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementSummaryResponse> getSummary() {
        return ApiResult.success(settlementUseCase.getSummary());
    }

    @GetMapping
    @Operation(summary = "정산 목록 조회",
            description = "정산 건을 표시 상태로 필터링하고 페이징해 조회합니다. ADMIN 권한이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SettlementListResponse.class))),
            @ApiResponse(responseCode = "400", description = "요청 값 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementListResponse> getList(
            @Parameter(description = "표시 상태 필터(미지정 시 전체)")
            @RequestParam(required = false) SettlementDisplayStatus status,
            @Parameter(description = "0-base 페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResult.success(settlementUseCase.getList(new SettlementListQuery(status, page, size)));
    }
}
