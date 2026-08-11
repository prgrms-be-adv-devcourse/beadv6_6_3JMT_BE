package com.prompthub.product.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "상품 수정 요청")
public record ProductUpdateRequest(
	@Schema(description = "상품명")
	@NotBlank String title,

	@Schema(description = "상품 유형(PROMPT|NOTION|PPT|EXCEL, 기본값 PROMPT). 등록 후 변경 불가", example = "PROMPT")
	String productType,

	@Schema(description = "대상 AI 모델(PROMPT 타입만 사용)")
	String model,

	@Schema(description = "상품 설명")
	@NotBlank String desc,

	@Schema(description = "가격")
	@NotNull @Min(0) Integer amount,

	@Schema(description = "프롬프트 원문(PROMPT 필수)")
	String content,

	@Schema(description = "산출물 파일 object key(PPT·EXCEL 필수)")
	String fileObjectKey,

	@Schema(description = "외부 노션 링크(NOTION 필수)")
	String externalUrl,

	@Schema(description = "썸네일 이미지 object key. 새 파일이면 temp key, 안 바꿨으면 기존 영구 key 그대로", example = "products/temp/<sellerId>/thumbnail/<uuid>.png")
	String thumbnailObjectKey,

	@Schema(description = "소개 이미지 object key 목록")
	List<String> imageObjectKeys,

	@Schema(description = "판매자 지정 태그 목록")
	List<String> tags,

	@Schema(description = "변경 사유. 실제 변경이 있으면 필수")
	String changeReason
) {
}
