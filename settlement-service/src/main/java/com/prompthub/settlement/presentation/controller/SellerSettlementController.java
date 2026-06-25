package com.prompthub.settlement.presentation.controller;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.settlement.application.dto.SellerSettlementListQuery;
import com.prompthub.settlement.application.usecase.SettlementUseCase;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SellerSettlementSummaryResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.YearMonth;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("${api.init}/sellers/me/settlements")
@RequiredArgsConstructor
@Tag(name = "Seller Settlement", description = "판매자 정산 조회 API")
public class SellerSettlementController {

	private final SettlementUseCase settlementUseCase;

	@GetMapping("/summary")
	@Operation(summary = "판매자 정산 요약 조회",
		description = "내 상점 상단 요약 지표(등록 프롬프트 수·누적 판매 건수·누적 거래액·누적 정산 지급액)를 조회합니다. SELLER 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공",
			content = @Content(schema = @Schema(implementation = SellerSettlementSummaryResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "SELLER 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerSettlementSummaryResponse> getMySummary(
		@Parameter(description = "판매자 ID(UUID)", in = ParameterIn.HEADER)
		@RequestHeader("X-User-Id") UUID sellerId) {
		return ApiResult.success(settlementUseCase.getMySummary(sellerId));
	}

	@GetMapping
	@Operation(summary = "판매자 본인 정산 내역 조회",
		description = "본인 정산 건을 표시 상태·기준 월로 필터링하고 페이징해 조회합니다. SELLER 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공",
			content = @Content(schema = @Schema(implementation = SellerSettlementListResponse.class))),
		@ApiResponse(responseCode = "400", description = "요청 값 오류",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "SELLER 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerSettlementListResponse> getMySettlements(
		@Parameter(description = "판매자 ID(UUID)", in = ParameterIn.HEADER)
		@RequestHeader("X-User-Id") UUID sellerId,
		@Parameter(description = "표시 상태 필터(미지정 시 전체)")
		@RequestParam(required = false) SettlementDisplayStatus status,
		@Parameter(description = "조회 기준 월(YYYY-MM)", example = "2026-06")
		@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth period,
		@Parameter(description = "0-base 페이지 번호") @RequestParam(defaultValue = "0") int page,
		@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "10") int size
	) {
		return ApiResult.success(settlementUseCase.getMySettlements(
			new SellerSettlementListQuery(sellerId, status, period, page, size)));
	}

	@PatchMapping("/{settlementId}/payout-request")
	@Operation(summary = "판매자 지급 신청",
		description = "승인 완료(지급 준비)된 본인 정산을 지급 신청(PAYOUT_REQUESTED)합니다. SELLER 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "지급 신청 성공",
			content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
		@ApiResponse(responseCode = "403", description = "본인 정산이 아님",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "지급 준비(READY) 상태가 아니라 신청 불가",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementStatusResponse> requestPayout(
		@Parameter(description = "판매자 ID(UUID)", in = ParameterIn.HEADER)
		@RequestHeader("X-User-Id") UUID sellerId,
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId
	) {
		return ApiResult.success(settlementUseCase.requestPayout(sellerId, settlementId));
	}
}
