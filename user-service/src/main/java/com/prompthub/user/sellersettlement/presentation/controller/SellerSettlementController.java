package com.prompthub.user.sellersettlement.presentation.controller;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementUseCase;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementStatusResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.YearMonth;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller Settlement", description = "판매자 정산 조회·지급요청 API")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v2/sellers/me/settlements")
@RequiredArgsConstructor
public class SellerSettlementController {

	private final SellerSettlementUseCase sellerSettlementUseCase;

	@GetMapping
	@Operation(summary = "판매자 본인 정산 내역 조회",
		description = "본인 정산 건을 표시 상태·기준 월로 필터링하고 페이징해 조회합니다. SELLER 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공",
			content = @Content(schema = @Schema(implementation = SellerSettlementListResponse.class))),
		@ApiResponse(responseCode = "400", description = "요청 값 오류",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "SELLER 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerSettlementListResponse> getMySettlements(
		@Parameter(hidden = true)
		@RequestHeader("X-User-Id") UUID sellerId,
		@Parameter(description = "표시 상태 필터(미지정 시 전체)")
		@RequestParam(required = false) SettlementDisplayStatus status,
		@Parameter(description = "조회 기준 월(YYYY-MM)", example = "2026-06")
		@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth period,
		@Parameter(description = "0-base 페이지 번호") @RequestParam(defaultValue = "0") int page,
		@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "10") int size
	) {
		return ApiResult.success(sellerSettlementUseCase.getMySettlements(
			new SellerSettlementListQuery(sellerId, status, period, page, size)));
	}

	@GetMapping("/summary")
	@Operation(summary = "판매자 정산 금액 요약 조회",
		description = "본인의 누적 총 거래액과 누적 정산 지급 완료 금액을 조회합니다. SELLER 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공",
			content = @Content(schema = @Schema(implementation = SellerSettlementSummaryResponse.class))),
		@ApiResponse(responseCode = "403", description = "SELLER 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerSettlementSummaryResponse> getMySummary(
		@Parameter(hidden = true)
		@RequestHeader("X-User-Id") UUID sellerId
	) {
		return ApiResult.success(sellerSettlementUseCase.getMySummary(sellerId));
	}

	@PatchMapping("/{settlementId}/payout-request")
	@Operation(summary = "판매자 지급 신청",
		description = "승인(APPROVED)된 본인 정산을 지급 신청(PAYOUT_REQUESTED)합니다. SELLER 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "지급 신청 성공",
			content = @Content(schema = @Schema(implementation = SellerSettlementStatusResponse.class))),
		@ApiResponse(responseCode = "403", description = "본인 정산이 아님",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "승인 상태가 아니라 신청 불가",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerSettlementStatusResponse> requestPayout(
		@Parameter(hidden = true)
		@RequestHeader("X-User-Id") UUID sellerId,
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId
	) {
		return ApiResult.success(sellerSettlementUseCase.requestPayout(sellerId, settlementId));
	}
}
