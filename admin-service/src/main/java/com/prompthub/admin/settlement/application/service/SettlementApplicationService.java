package com.prompthub.admin.settlement.application.service;

import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.application.usecase.SettlementUseCase;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementApplicationService implements SettlementUseCase {

	private final SettlementQueryRepository settlementQueryRepository;

	@Override
	public SettlementListResponse getList(SettlementListQuery query) {
		SettlementQueryRepository.SettlementPage result =
			settlementQueryRepository.findPage(query.status(), query.page(), query.size());
		return SettlementListResponse.from(
			result.content(), result.totalElements(), query.page(), query.size());
	}
}
