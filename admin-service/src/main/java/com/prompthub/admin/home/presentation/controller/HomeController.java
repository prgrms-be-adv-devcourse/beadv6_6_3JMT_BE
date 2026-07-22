package com.prompthub.admin.home.presentation.controller;

import com.prompthub.admin.home.application.usecase.HomeUseCase;
import com.prompthub.admin.home.presentation.dto.response.HomeResponse;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
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

	private final HomeUseCase homeUseCase;

	@GetMapping
	@Operation(
		summary = "어드민 홈 조회",
		description = "홈 KPI, 최근 7일 거래, 정산 승인 대기, 검수 대기 상품을 조회합니다."
	)
	public ApiResult<HomeResponse> getHome() {
		return ApiResult.success(HomeResponse.from(homeUseCase.getHome()));
	}
}
