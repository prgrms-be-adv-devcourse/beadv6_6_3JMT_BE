package com.prompthub.settlement.presentation.controller;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import com.prompthub.settlement.application.usecase.GetSettlementJobStatusUseCase;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.global.web.AuthHeaders;
import com.prompthub.settlement.presentation.dto.request.RunSettlementJobRequest;
import com.prompthub.settlement.presentation.dto.response.SettlementJobResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementJobStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "settlement.manual-api", name = "enabled", havingValue = "true")
@RequestMapping("${api.init}/admin/settlements/batch")
@RequiredArgsConstructor
@Tag(name = "Settlement Batch", description = "정산 배치잡 API")
public class SettlementBatchController {

	private final RunSettlementBatchUseCase runSettlementBatchUseCase;
	private final GetSettlementJobStatusUseCase getSettlementJobStatusUseCase;

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "정산 배치잡 실행(비동기)",
		description = "정산 대상 월의 미정산 PAID 주문을 정산하는 Batch Job을 비동기로 실행 접수합니다. "
			+ "응답은 잡 실행 식별자와 시작 상태만 담으며, 완료 여부는 별도로 조회합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "202", description = "실행 접수(비동기 시작)",
			content = @Content(schema = @Schema(implementation = SettlementJobResponse.class))),
		@ApiResponse(responseCode = "400", description = "요청 값 오류",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "500", description = "정산 배치 잡 실행 실패",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementJobResponse> run(
		@Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) UUID actorId,
		@Valid @RequestBody RunSettlementJobRequest request
	) {
		SettlementJobResult result = runSettlementBatchUseCase.run(request.toCommand(actorId));
		return ApiResult.success(SettlementJobResponse.from(result));
	}

	@GetMapping("/{jobExecutionId}")
	@Operation(summary = "정산 배치잡 상태 조회",
		description = "비동기로 실행한 정산 배치 잡의 진행/완료 상태를 조회합니다. "
			+ "프론트는 이 API를 폴링해 완료(COMPLETED)/실패(FAILED)를 감지합니다. ADMIN 권한이 필요합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공",
			content = @Content(schema = @Schema(implementation = SettlementJobStatusResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "잡 실행 이력 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SettlementJobStatusResponse> getStatus(
		@Parameter(description = "조회할 Job Execution ID") @PathVariable Long jobExecutionId
	) {
		SettlementJobStatusResult result = getSettlementJobStatusUseCase.getStatus(jobExecutionId);
		return ApiResult.success(SettlementJobStatusResponse.from(result));
	}
}
