package com.prompthub.ai.inspection.application.port;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;

public interface ProductInspectionAiPort {

	InspectionVerdict inspect(ProductInspectionRequest request);
}
