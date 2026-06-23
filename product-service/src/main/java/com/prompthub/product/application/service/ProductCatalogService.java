package com.prompthub.product.application.service;

import com.prompthub.product.presentation.dto.ProductListItemResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProductCatalogService {

	private static final List<ProductListItemResponse> PRODUCTS = List.of(
		product("11111111-1111-1111-1111-111111111111", "사진 같은 제품 목업 생성기", "image", "image",
			"Midjourney v6", 5900, null, 4.9, 1240, "비주얼프로", "신규",
			"광고와 상세페이지에 바로 쓰는 제품 목업 프롬프트"),
		product("22222222-2222-2222-2222-222222222222", "전환율 높은 랜딩 카피 작성", "writing", "pen-line",
			"GPT-4o", 4900, null, 4.8, 980, "카피랩", "인기",
			"상품 장점과 고객 페르소나를 반영한 랜딩 페이지 카피 세트"),
		product("33333333-3333-3333-3333-333333333333", "React 컴포넌트 리팩토링 도우미", "coding", "code-xml",
			"Claude 3.5", 7900, null, 4.7, 760, "프론트마스터", null,
			"컴포넌트 분리, 상태 정리, 타입 개선을 위한 개발 프롬프트"),
		product("44444444-4444-4444-4444-444444444444", "30일 SNS 콘텐츠 캘린더", "marketing", "megaphone",
			"GPT-4o", 3900, null, 4.6, 640, "마케팅스튜디오", null,
			"브랜드 톤에 맞춰 한 달치 SNS 소재를 생성하는 프롬프트"),
		product("55555555-5555-5555-5555-555555555555", "ChatGPT 프롬프트 마스터팩", "chatbot", "message-circle",
			"ChatGPT", 9900, 12900, 4.9, 1530, "프롬프트랩", "베스트",
			"업무 자동화와 상담 시나리오를 위한 범용 프롬프트 묶음"),
		product("66666666-6666-6666-6666-666666666666", "웹사이트 데이터 인사이트 요약", "data", "bar-chart-3",
			"Claude 3.5", 4900, null, 4.5, 410, "데이터노트", null,
			"원본 데이터를 넣으면 핵심 지표와 개선 포인트를 요약"),
		product("77777777-7777-7777-7777-777777777777", "유튜브 썸네일 카피 생성기", "marketing", "megaphone",
			"GPT-4o", 3900, null, 4.4, 520, "콘텐츠메이커", null,
			"클릭을 유도하는 제목과 썸네일 문구를 빠르게 생성"),
		product("88888888-8888-8888-8888-888888888888", "이미지 광고 콘셉트 보드", "image", "image",
			"Midjourney v6", 6900, null, 4.7, 690, "비주얼프로", null,
			"브랜드 무드에 맞는 광고 이미지 콘셉트를 설계")
	);

	public PageResponse<ProductListItemResponse> getProducts(
		String q,
		String category,
		String sort,
		int page,
		int size
	) {
		String keyword = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		String selectedCategory = category == null || category.isBlank() ? "all" : category;

		List<ProductListItemResponse> filtered = PRODUCTS.stream()
			.filter(product -> "all".equals(selectedCategory) || product.category().equals(selectedCategory))
			.filter(product -> keyword.isBlank()
				|| product.title().toLowerCase(Locale.ROOT).contains(keyword)
				|| product.desc().toLowerCase(Locale.ROOT).contains(keyword))
			.sorted(resolveComparator(sort))
			.toList();

		int normalizedPage = Math.max(page, 1);
		int normalizedSize = Math.max(size, 1);
		int fromIndex = Math.min((normalizedPage - 1) * normalizedSize, filtered.size());
		int toIndex = Math.min(fromIndex + normalizedSize, filtered.size());
		List<ProductListItemResponse> data = filtered.subList(fromIndex, toIndex);
		boolean hasNext = toIndex < filtered.size();

		return PageResponse.success(data, normalizedPage, normalizedSize, filtered.size(), hasNext);
	}

	private static Comparator<ProductListItemResponse> resolveComparator(String sort) {
		if ("rating".equals(sort)) {
			return Comparator.comparing(ProductListItemResponse::rating).reversed();
		}
		if ("price-asc".equals(sort)) {
			return Comparator.comparing(ProductListItemResponse::amount);
		}
		if ("price-desc".equals(sort)) {
			return Comparator.comparing(ProductListItemResponse::amount).reversed();
		}
		return Comparator.comparing(ProductListItemResponse::salesCount).reversed();
	}

	private static ProductListItemResponse product(
		String id,
		String title,
		String category,
		String icon,
		String model,
		int amount,
		Integer originalAmount,
		double rating,
		int salesCount,
		String seller,
		String badge,
		String desc
	) {
		return new ProductListItemResponse(
			UUID.fromString(id),
			title,
			category,
			icon,
			model,
			amount,
			originalAmount,
			rating,
			salesCount,
			seller,
			UUID.fromString("99999999-9999-9999-9999-999999999999"),
			badge,
			desc,
			null,
			LocalDateTime.of(2026, 5, 1, 0, 0),
			LocalDateTime.of(2026, 6, 1, 0, 0)
		);
	}
}
