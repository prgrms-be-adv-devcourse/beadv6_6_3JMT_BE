package com.prompthub.admin.settlement.presentation.controller;

import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.application.usecase.SettlementUseCase;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementDetailResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse;
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
import java.time.YearMonth;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("${api.init}/admin/settlements")
@RequiredArgsConstructor
@Tag(name = "Admin Settlement", description = "어드민 정산 관리 API (settlement-service 에서 이관)")
@Validated
public class SettlementController {

	private final SettlementUseCase settlementUseCase;

	@GetMapping("/summary")
	@Operation(summary = "정산 요약 카드 조회",
		description = "정산 관리 화면 상단 요약 카드(상태별 합계·건수)를 조회합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공",
			content = @Content(schema = @Schema(implementation = SettlementSummaryResponse.class))),
		@ApiResponse(responseCode = "400", description = "요청 값 오류",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementSummaryResponse> getSummary(
		@Parameter(description = "정산 월(YYYY-MM, 미지정 시 전체)", example = "2026-07")
		@RequestParam(required = false)
		@DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth
	) {
		return ApiResult.success(settlementUseCase.getSummary(settlementMonth));
	}

	@GetMapping
	@Operation(summary = "판매자 월별 정산 목록 조회",
		description = "정산을 판매자·월별로 집계하고 상태·정산 월로 필터링해 조회합니다. ADMIN 권한이 필요합니다.")
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
		@Parameter(description = "정산 월(YYYY-MM)", example = "2026-07")
		@RequestParam(required = false)
		@DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth,
		@Parameter(description = "0-base 페이지 번호")
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@Parameter(description = "페이지 크기")
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResult.success(settlementUseCase.getList(
			new SettlementListQuery(status, settlementMonth, page, size)));
	}

	@GetMapping("/sellers/{sellerId}/months/{settlementMonth}")
	@Operation(summary = "판매자 월별 정산 상세 조회",
		description = "판매자와 정산 월에 포함된 주간 정산과 가능한 액션을 조회합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공",
			content = @Content(schema = @Schema(implementation = SettlementDetailResponse.class))),
		@ApiResponse(responseCode = "400", description = "요청 값 오류",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 월 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementDetailResponse> getDetail(
		@Parameter(description = "판매자 ID(UUID)") @PathVariable UUID sellerId,
		@Parameter(description = "정산 월(YYYY-MM)", example = "2026-07")
		@PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth
	) {
		return ApiResult.success(
			settlementUseCase.getDetail(sellerId, settlementMonth));
	}

	@PatchMapping("/{settlementId}/approve")
	@Operation(summary = "정산 승인",
		description = "승인 대기 상태의 정산을 승인 완료(APPROVED)로 전환합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "승인 성공",
			content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "전이 불가 상태",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementStatusResponse> approve(
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
		@Parameter(description = "요청 수행자 ID(UUID)", in = ParameterIn.HEADER) @RequestHeader("X-User-Id") UUID actorId) {
		log.info("정산 승인 요청 - settlementId={}, actorId={}", settlementId, actorId);
		return ApiResult.success(settlementUseCase.approve(settlementId));
	}

	@PatchMapping("/{settlementId}/hold")
	@Operation(summary = "정산 승인 보류",
		description = "승인 대기 상태의 정산을 승인 보류(APPROVAL_ON_HOLD)로 전환합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "보류 성공",
			content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "전이 불가 상태",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementStatusResponse> hold(
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
		@Parameter(description = "요청 수행자 ID(UUID)", in = ParameterIn.HEADER) @RequestHeader("X-User-Id") UUID actorId) {
		log.info("정산 승인 보류 요청 - settlementId={}, actorId={}", settlementId, actorId);
		return ApiResult.success(settlementUseCase.hold(settlementId));
	}

	@PatchMapping("/{settlementId}/release-hold")
	@Operation(summary = "정산 승인 보류 해제",
		description = "승인 보류 상태의 정산을 승인 대기(WAITING)로 되돌립니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "보류 해제 성공",
			content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "전이 불가 상태",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementStatusResponse> releaseHold(
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
		@Parameter(description = "요청 수행자 ID(UUID)", in = ParameterIn.HEADER) @RequestHeader("X-User-Id") UUID actorId) {
		log.info("정산 승인 보류 해제 요청 - settlementId={}, actorId={}", settlementId, actorId);
		return ApiResult.success(settlementUseCase.releaseHold(settlementId));
	}

	@PatchMapping("/{settlementId}/payout")
	@Operation(summary = "정산 지급",
		description = "지급 신청(PAYOUT_REQUESTED)된 정산을 지급 완료(PAID)로 전환합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "지급 성공",
			content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "전이 불가 상태",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementStatusResponse> payout(
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
		@Parameter(description = "요청 수행자 ID(UUID)", in = ParameterIn.HEADER) @RequestHeader("X-User-Id") UUID actorId) {
		log.info("정산 지급 요청 - settlementId={}, actorId={}", settlementId, actorId);
		return ApiResult.success(settlementUseCase.payout(settlementId));
	}

	@PatchMapping("/{settlementId}/payout-hold")
	@Operation(summary = "정산 지급 보류",
		description = "지급 신청(PAYOUT_REQUESTED)된 정산을 지급 보류(PAYOUT_ON_HOLD)로 전환합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "지급 보류 성공",
			content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "전이 불가 상태",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementStatusResponse> payoutHold(
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
		@Parameter(description = "요청 수행자 ID(UUID)", in = ParameterIn.HEADER) @RequestHeader("X-User-Id") UUID actorId) {
		log.info("정산 지급 보류 요청 - settlementId={}, actorId={}", settlementId, actorId);
		return ApiResult.success(settlementUseCase.payoutHold(settlementId));
	}

	@PatchMapping("/{settlementId}/payout-hold/release")
	@Operation(summary = "정산 지급 보류 해제",
		description = "지급 보류(PAYOUT_ON_HOLD)된 정산을 지급 신청(PAYOUT_REQUESTED)으로 되돌립니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "지급 보류 해제 성공",
			content = @Content(schema = @Schema(implementation = SettlementStatusResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "전이 불가 상태",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementStatusResponse> releasePayoutHold(
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
		@Parameter(description = "요청 수행자 ID(UUID)", in = ParameterIn.HEADER) @RequestHeader("X-User-Id") UUID actorId) {
		log.info("정산 지급 보류 해제 요청 - settlementId={}, actorId={}", settlementId, actorId);
		return ApiResult.success(settlementUseCase.releasePayoutHold(settlementId));
	}

	@PatchMapping("/{settlementId}/cancel")
	@Operation(summary = "정산 취소",
		description = "지급 완료(PAID) 전 정산을 취소하고, 묶인 소스 라인을 풀어 재정산 대상으로 되돌립니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "취소 성공",
			content = @Content(schema = @Schema(implementation = SettlementResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "정산 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "이미 지급 완료됨 / 이미 취소됨",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementResponse> cancel(
		@Parameter(description = "정산 ID(UUID)") @PathVariable UUID settlementId,
		@Parameter(description = "요청 수행자 ID(UUID)", in = ParameterIn.HEADER) @RequestHeader("X-User-Id") UUID actorId) {
		log.info("정산 취소 요청 - settlementId={}, actorId={}", settlementId, actorId);
		return ApiResult.success(settlementUseCase.cancel(settlementId));
	}
}
