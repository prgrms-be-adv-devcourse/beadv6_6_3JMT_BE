package com.prompthub.product.application.service;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.model.vo.ProductContentHash;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.infra.persistence.ProductJpaRepository;
import com.prompthub.product.infra.persistence.ProductRepositoryAdapter;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.support.PostgresIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ProductSellerService}가 실제 {@link ProductRepositoryAdapter} + 실제 Postgres +
 * 실제 {@code trg_content_hash_at} 트리거와 맞물렸을 때도 복제 탐지가 배선대로 동작하는지 검증한다.
 *
 * <p>기존 테스트는 리포지토리 쿼리(순수 Postgres, {@code ProductJpaRepositoryTest})와 서비스 로직
 * (리포지토리 모킹, {@code ProductSellerServiceTest})을 따로만 검증했다 — adapter 위임, 트랜잭션
 * 경계, Hibernate flush 타이밍(merge 직후 아직 flush되지 않은 자기 자신의 row를 서브쿼리로
 * 되읽는 지점) 같은 배선 버그는 둘 중 어느 테스트도 잡지 못한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductDuplicateDetectionIntegrationTest extends PostgresIntegrationTestSupport {

	@Autowired
	private ProductJpaRepository productJpaRepository;

	@Autowired
	private EntityManager entityManager;

	private ProductRepository productRepository;
	private ProductEventProducer productEventProducer;
	private StorageClient storageClient;
	private ProductSellerService productSellerService;

	@BeforeEach
	void setUp() {
		// Spring이 아니라 순수 자바 조립 — Kafka·전체 컨텍스트 부팅 없이 실제 리포지토리 배선만 검증한다.
		productRepository = Mockito.spy(new ProductRepositoryAdapter(productJpaRepository));
		productEventProducer = Mockito.mock(ProductEventProducer.class);
		storageClient = Mockito.mock(StorageClient.class);
		productSellerService = new ProductSellerService(productRepository, productEventProducer, storageClient);
	}

	@Test
	@DisplayName("판매자 B가 A와 공백/대소문자만 다른 같은 본문을 제출하면 A의 상품 id가 duplicateOfProductId로 발행된다")
	void submitForReview_crossSellerNormalizedDuplicate_publishesEarlierProductId() {
		UUID sellerA = UUID.randomUUID();
		UUID sellerB = UUID.randomUUID();
		UUID productAId = null;
		UUID productBId = null;
		try {
			// 판매자 A: 원본 등록 → 승인(ON_SALE)까지 간 것으로 세팅
			ProductCreateResponse createdA = productSellerService.createProduct(
				sellerA, promptRequest("원본", "Hello   World\n\tGPT"));
			productAId = createdA.productId();
			setStatus(productAId, ProductStatus.ON_SALE);
			commit();

			beginNewTransaction();
			// 판매자 B: 공백/대소문자만 다른 같은 본문 — flush 없이 바로 제출까지 실제 배선을 탄다
			ProductCreateResponse createdB = productSellerService.createProduct(
				sellerB, promptRequest("복제", "hello world gpt"));
			productBId = createdB.productId();

			productSellerService.submitForReview(sellerB, productBId);

			ArgumentCaptor<UUID> duplicateCaptor = ArgumentCaptor.forClass(UUID.class);
			then(productEventProducer).should().publishReviewRequested(
				any(Product.class), duplicateCaptor.capture(), any(), any());
			assertThat(duplicateCaptor.getValue()).isEqualTo(productAId);
		} finally {
			cleanUp(productAId, productBId);
		}
	}

	@Test
	@DisplayName("판매자 A 자신의 재제출은 자기 자신을 중복으로 잡지 않는다")
	void submitForReview_sameSellerResubmit_doesNotFlagSelf() {
		UUID seller = UUID.randomUUID();
		UUID product1Id = null;
		UUID product2Id = null;
		try {
			ProductCreateResponse created1 = productSellerService.createProduct(
				seller, promptRequest("원본", "같은 판매자 재제출 원문"));
			product1Id = created1.productId();
			setStatus(product1Id, ProductStatus.ON_SALE);
			commit();

			beginNewTransaction();
			ProductCreateResponse created2 = productSellerService.createProduct(
				seller, promptRequest("재수정", "같은 판매자 재제출 원문"));
			product2Id = created2.productId();

			productSellerService.submitForReview(seller, product2Id);

			then(productEventProducer).should().publishReviewRequested(
				any(Product.class), isNull(), any(), any());
		} finally {
			cleanUp(product1Id, product2Id);
		}
	}

	@ParameterizedTest
	@DisplayName("원본이 ON_SALE/PENDING_REVIEW가 아니면(DRAFT·REJECTED·STOPPED·SUPERSEDED) 중복으로 안 잡는다")
	@EnumSource(value = ProductStatus.class, names = {"DRAFT", "REJECTED", "STOPPED", "SUPERSEDED"})
	void submitForReview_originalNotOnSaleOrPendingReview_doesNotFlagAsDuplicate(ProductStatus originalStatus) {
		UUID sellerA = UUID.randomUUID();
		UUID sellerB = UUID.randomUUID();
		UUID productAId = null;
		UUID productBId = null;
		try {
			ProductCreateResponse createdA = productSellerService.createProduct(
				sellerA, promptRequest("원본", "노출 대상 아닌 원문 " + originalStatus));
			productAId = createdA.productId();
			setStatus(productAId, originalStatus);
			commit();

			beginNewTransaction();
			ProductCreateResponse createdB = productSellerService.createProduct(
				sellerB, promptRequest("복제", "노출 대상 아닌 원문 " + originalStatus));
			productBId = createdB.productId();

			productSellerService.submitForReview(sellerB, productBId);

			then(productEventProducer).should().publishReviewRequested(
				any(Product.class), isNull(), any(), any());
		} finally {
			cleanUp(productAId, productBId);
		}
	}

	@Test
	@DisplayName("본문이 없는 유형(NOTION)은 제출 시 중복 조회 자체를 호출하지 않는다")
	void submitForReview_nonPromptType_neverQueriesForDuplicate() {
		UUID seller = UUID.randomUUID();
		UUID productId = null;
		try {
			ProductCreateResponse created = productSellerService.createProduct(seller, notionRequest("노션 상품"));
			productId = created.productId();

			productSellerService.submitForReview(seller, productId);

			then(productRepository).should(never()).findDuplicateOfProductId(any(), any(), any());
			then(productEventProducer).should().publishReviewRequested(
				any(Product.class), isNull(), any(), any());
		} finally {
			cleanUp(productId, null);
		}
	}

	/**
	 * 처음엔 Java {@code ProductContentHash.normalize()}가 {@code String.strip()}(유니코드
	 * 공백 인식) 다음에 ASCII 전용 {@code \s} 정규식을 돌려서, U+3000(전각 공백)이 선행·후행이면
	 * 지워지고 본문 중간이면 안 지워지는 비대칭이 있었다. 게다가 실측해보니 <b>Postgres의
	 * {@code \s} 메타클래스는 UTF8 인코딩에서 U+3000을 공백으로 인식한다</b>(반면 NBSP(U+00A0)는
	 * 인식하지 않는다) — Java의 ASCII 전용 {@code \s}와 범위가 달라, {@code strip()}을
	 * {@code trim()}으로만 바꾸는 걸로는 못 맞춘다(2026-07-30 발견). 로캘/인코딩에 의존하는
	 * {@code \s} 대신 양쪽 다 명시적 문자 클래스 {@code [ \t\n\r\f\v]}로 고정해 항상 같은
	 * 결과를 내게 했다 — V5 마이그레이션의 정규식과 반드시 같은 문자 클래스를 써야 한다.
	 */
	@Test
	@DisplayName("전각 공백(U+3000) 포함 본문도 Java normalize()와 Postgres 정규화가 같은 해시를 낸다")
	void javaAndPostgresNormalize_agreeOnFullWidthSpace() {
		ProductContent content = promptContent("유니코드", 1000, "　Hello　World　");
		Product product = productJpaRepository.saveAndFlush(
			Product.create(UUID.randomUUID(), UUID.randomUUID(), content));
		try {
			Object fromDb = entityManager
				.createNativeQuery("""
					SELECT encode(sha256(convert_to(
					           lower(trim(regexp_replace(content, '[ \\t\\n\\r\\f\\v]+', ' ', 'g'))), 'UTF8')), 'hex')
					  FROM product WHERE id = :id
					""")
				.setParameter("id", product.getId())
				.getSingleResult();

			assertThat(fromDb.toString())
				.as("Java normalize()와 Postgres 정규화가 U+3000(전각 공백)에서 같은 해시를 내야 한다")
				.isEqualTo(ProductContentHash.of(content));
		} finally {
			cleanUp(product.getId(), null);
		}
	}

	@Test
	@DisplayName("줄바꿈 없는 공백(U+00A0)에서도 Java normalize()와 Postgres 정규화가 같은 해시를 낸다")
	void javaAndPostgresNormalize_agreeOnNonBreakingSpace() {
		ProductContent content = promptContent("유니코드", 1000, " Hello World ");
		Product product = productJpaRepository.saveAndFlush(
			Product.create(UUID.randomUUID(), UUID.randomUUID(), content));
		try {
			Object fromDb = entityManager
				.createNativeQuery("""
					SELECT encode(sha256(convert_to(
					           lower(trim(regexp_replace(content, '[ \\t\\n\\r\\f\\v]+', ' ', 'g'))), 'UTF8')), 'hex')
					  FROM product WHERE id = :id
					""")
				.setParameter("id", product.getId())
				.getSingleResult();

			assertThat(fromDb.toString())
				.as("Java normalize()와 Postgres 정규화가 U+00A0(줄바꿈 없는 공백)에서 같은 해시를 내야 한다")
				.isEqualTo(ProductContentHash.of(content));
		} finally {
			cleanUp(product.getId(), null);
		}
	}

	private void setStatus(UUID productId, ProductStatus status) {
		Product product = productJpaRepository.findById(productId).orElseThrow();
		ReflectionTestUtils.setField(product, "status", status);
		productJpaRepository.saveAndFlush(product);
	}

	/**
	 * 호출 시점에 활성 트랜잭션이 하나 있다고 가정한다(기본 테스트 트랜잭션이든, 테스트 안에서
	 * {@link #beginNewTransaction()}으로 새로 연 것이든 — 어느 쪽도 아직 끝내지 않은 채 여기로
	 * 온다). 그 트랜잭션에서 바로 정리하고 커밋한 뒤, 테스트 프레임워크의 표준 종료 처리가
	 * 기대하는 활성 트랜잭션을 다시 열어둔다.
	 */
	private void cleanUp(UUID id1, UUID id2) {
		List<UUID> ids = id2 == null ? List.of(id1) : List.of(id1, id2);
		productJpaRepository.deleteAllById(ids);
		commit();
		TestTransaction.start();
	}

	private void commit() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
	}

	private void beginNewTransaction() {
		TestTransaction.start();
	}

	private ProductCreateRequest promptRequest(String title, String content) {
		return new ProductCreateRequest(
			title, "PROMPT", "gpt-5", "설명", 1000, content, null, null, null, List.of(), List.of());
	}

	private ProductCreateRequest notionRequest(String title) {
		return new ProductCreateRequest(
			title, "NOTION", "model", "설명", 1000, null, null, "https://notion.so/x", null, List.of(), List.of());
	}
}
