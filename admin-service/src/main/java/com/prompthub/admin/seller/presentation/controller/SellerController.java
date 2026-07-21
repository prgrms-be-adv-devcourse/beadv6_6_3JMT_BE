package com.prompthub.admin.seller.presentation.controller;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterListQuery;
import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.application.usecase.SellerUseCase;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.presentation.dto.request.RejectSellerRegisterRequest;
import com.prompthub.admin.seller.presentation.dto.response.SellerRegisterResponse;
import com.prompthub.admin.seller.presentation.dto.response.SellerRegisterReviewResponse;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${api.init}/admin")
@RequiredArgsConstructor
@Tag(name = "Admin Seller", description = "관리자 판매자 등록 신청 심사 API (user-service 에서 이관)")
@SecurityRequirement(name = "gatewayHeaders")
public class SellerController {

	private final SellerUseCase sellerUseCase;

	@GetMapping("/sellers/register")
	@Operation(summary = "판매자 신청 목록 조회", description = "상태 필터 및 페이지네이션 지원. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public PageResponse<SellerRegisterResponse> listSellerRegisters(
		@Parameter(description = "신청 상태 필터 (PENDING | APPROVED | REJECTED | ALL)", example = "ALL")
		@RequestParam(defaultValue = "ALL") String status,
		@Parameter(description = "페이지 번호 (1부터 시작)", example = "1")
		@RequestParam(defaultValue = "1") int page,
		@Parameter(description = "페이지당 항목 수", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		SellerRegisterListQuery query = new SellerRegisterListQuery(parseStatus(status), page, size);

		SellerRegisterPageResult result = sellerUseCase.listSellerRegisters(query);

		List<SellerRegisterResponse> responseData = result.items().stream()
			.map(SellerRegisterResponse::from)
			.toList();

		return PageResponse.success(responseData, result.page(), result.size(), result.total(), result.hasNext());
	}

	@PatchMapping("/sellers/register/{registerId}/approve")
	@Operation(summary = "판매자 신청 승인", description = "승인 시 SELLER 역할 부여. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "승인 성공"),
		@ApiResponse(responseCode = "400", description = "이미 심사된 신청",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "신청 내역을 찾을 수 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerRegisterReviewResponse> approveSeller(
		@Parameter(description = "판매자 등록 신청 ID") @PathVariable UUID registerId
	) {
		SellerRegisterReviewResult result = sellerUseCase.approve(new ApproveSellerCommand(registerId));
		return ApiResult.success(SellerRegisterReviewResponse.from(result));
	}

	@PatchMapping("/sellers/register/{registerId}/reject")
	@Operation(summary = "판매자 신청 반려", description = "반려 사유를 포함하여 반려 처리. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "반려 성공"),
		@ApiResponse(responseCode = "400", description = "요청 값 오류 또는 이미 심사된 신청",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "신청 내역을 찾을 수 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerRegisterReviewResponse> rejectSeller(
		@Parameter(description = "판매자 등록 신청 ID") @PathVariable UUID registerId,
		@Valid @RequestBody RejectSellerRegisterRequest request
	) {
		SellerRegisterReviewResult result = sellerUseCase.reject(
			new RejectSellerCommand(registerId, request.rejectReason()));
		return ApiResult.success(SellerRegisterReviewResponse.from(result));
	}

	private static SellerRegisterStatus parseStatus(String statusParam) {
		return switch (statusParam.toUpperCase()) {
			case "PENDING" -> SellerRegisterStatus.PENDING;
			case "APPROVED" -> SellerRegisterStatus.APPROVED;
			case "REJECTED" -> SellerRegisterStatus.REJECTED;
			case "ALL" -> null;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}
}
