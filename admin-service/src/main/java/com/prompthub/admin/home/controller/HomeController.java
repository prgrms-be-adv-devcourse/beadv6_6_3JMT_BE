package com.prompthub.admin.home.controller;

import com.prompthub.admin.home.service.HomeService;
import com.prompthub.admin.home.dto.response.HomeResponse;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/admin/home")
@RequiredArgsConstructor
@Tag(name = "Admin Home", description = "어드민 홈 통합 조회 API")
@SecurityRequirement(name = "gatewayHeaders")
public class HomeController {

	private final HomeService homeApplicationService;

	@GetMapping
	@Operation(
		summary = "어드민 홈 조회",
		description = "홈 KPI, 최근 7일 거래, 정산 승인 대기, 검수 대기 상품을 조회합니다."
	)
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<HomeResponse> getHome() {
		return ApiResult.success(HomeResponse.from(homeApplicationService.getHome()));
	}
}
