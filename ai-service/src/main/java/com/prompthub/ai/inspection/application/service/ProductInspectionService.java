package com.prompthub.ai.inspection.application.service;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.port.ProductInspectionAiPort;
import com.prompthub.ai.inspection.application.usecase.ProductInspectionUseCase;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.InspectionEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductInspectionService implements ProductInspectionUseCase {

	private final ProductInspectionAiPort aiPort;
	private final InspectionEventProducer inspectionEventProducer;

	@Override
	public void inspect(ProductInspectionRequest request) {
		InspectionVerdict verdict = aiPort.inspect(request);
		inspectionEventProducer.publish(request.productId(), verdict.approved(), verdict.rejectionReason());
		log.info("상품 검수 완료. productId={}, approved={}", request.productId(), verdict.approved());
	}
}
