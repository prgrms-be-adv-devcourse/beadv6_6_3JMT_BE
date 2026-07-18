package com.prompthub.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.dto.SettlementJobStatusResult;
import com.prompthub.settlement.application.usecase.GetSettlementJobStatusUseCase;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.global.web.AuthHeaders;
import io.swagger.v3.oas.models.OpenAPI;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.cloud.config.enabled=false",
	"spring.cloud.config.fail-fast=false",
	"settlement.manual-api.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SettlementManualApiIntegrationTest {

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000394");
	private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 7, 18, 10, 0);

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OpenAPI openAPI;

	@MockitoBean
	private RunSettlementBatchUseCase runSettlementBatchUseCase;

	@MockitoBean
	private GetSettlementJobStatusUseCase getSettlementJobStatusUseCase;

	@Test
	@DisplayName("수동 API 기능 플래그를 켜면 컨트롤러와 OpenAPI 빈을 생성한다")
	void manualApiEnabled_createsControllerAndOpenApiBeans() {
		assertThat(applicationContext.containsBean("settlementBatchController")).isTrue();
		assertThat(applicationContext.containsBean("settlementOpenAPI")).isTrue();
	}

	@Test
	@DisplayName("X-User-Id만으로 수동 정산 배치를 실행 접수한다")
	void run_withUserIdAndWithoutRole_acceptsManualJob() throws Exception {
		given(runSettlementBatchUseCase.run(any()))
			.willReturn(new SettlementJobResult(1024L, "settlementJob", "STARTING", START_TIME));

		mockMvc.perform(post("/api/v2/admin/settlements/batch")
				.header(AuthHeaders.USER_ID, ACTOR_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"period":"2026-06"}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.jobExecutionId").value(1024));

		then(runSettlementBatchUseCase).should().run(argThat(command ->
			command.period().equals(YearMonth.of(2026, 6))
				&& command.actorId().equals(ACTOR_ID)
				&& command.triggerType() == TriggerType.MANUAL));
	}

	@Test
	@DisplayName("수동 실행 요청에 X-User-Id가 없으면 400을 반환한다")
	void run_withoutUserId_returnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/v2/admin/settlements/batch")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"period":"2026-06"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("S-003"));

		then(runSettlementBatchUseCase).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("사용자 식별 헤더 없이 수동 배치 실행 상태를 조회한다")
	void getStatus_withoutIdentityHeaders_returnsJobStatus() throws Exception {
		given(getSettlementJobStatusUseCase.getStatus(1024L))
			.willReturn(new SettlementJobStatusResult(
				1024L,
				"settlementJob",
				"COMPLETED",
				"COMPLETED",
				START_TIME,
				START_TIME.plusSeconds(12),
				null));

		mockMvc.perform(get("/api/v2/admin/settlements/batch/{jobExecutionId}", 1024L))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("COMPLETED"));

		then(getSettlementJobStatusUseCase).should().getStatus(1024L);
	}

	@Test
	@DisplayName("OpenAPI 보안 스키마에는 X-User-Id만 노출한다")
	void openApi_exposesOnlyUserIdSecurityScheme() {
		assertThat(openAPI.getComponents().getSecuritySchemes().keySet())
			.containsExactly(AuthHeaders.USER_ID);
		assertThat(openAPI.getSecurity()).hasSize(1);
		assertThat(openAPI.getSecurity().getFirst().keySet())
			.containsExactly(AuthHeaders.USER_ID);
	}
}
