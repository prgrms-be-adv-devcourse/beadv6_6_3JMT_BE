package com.prompthub.ai.inspection.application.usecase;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;

public interface ProductInspectionUseCase {

	void inspect(ProductInspectionRequest request);
}
