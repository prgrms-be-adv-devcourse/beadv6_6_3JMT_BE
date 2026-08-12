package com.prompthub.search.application.embedding;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class ProductEmbeddingUpdaterTest {

	@Mock
	private EmbeddingClient embeddingClient;

	@Mock
	private ProductRepository productRepository;

	private ProductEmbeddingUpdater updater;
	private ListAppender<ILoggingEvent> logAppender;

	@BeforeEach
	void setUp() {
		updater = new ProductEmbeddingUpdater(embeddingClient, productRepository);

		Logger logger = (Logger) LoggerFactory.getLogger(ProductEmbeddingUpdater.class);
		logger.setLevel(Level.WARN);
		logAppender = new ListAppender<>();
		logAppender.start();
		logger.addAppender(logAppender);
	}

	@AfterEach
	void detachLogAppender() {
		((Logger) LoggerFactory.getLogger(ProductEmbeddingUpdater.class)).detachAppender(logAppender);
	}

	@Test
	@DisplayName("저장된 해시가 원문 해시와 같으면 임베딩을 다시 만들지 않는다")
	void skipsWhenHashUnchanged() {
		Product product = product("이름");
		String currentHash = EmbeddingSource.of(product).hash();
		given(productRepository.findEmbeddingSourceHashes(List.of(product.getId())))
			.willReturn(Map.of(product.getId(), currentHash));

		Map<UUID, float[]> refreshed = updater.refreshAndGet(List.of(product));

		// 이 검증이 이 클래스의 존재 이유다. 재조정 배치는 updated_at 기준으로 대상을 고르는데,
		// 조회수 증가만으로도 그 값이 바뀐다. 가드가 없으면 상품을 열어보기만 해도 OpenAI를 부른다.
		then(embeddingClient).should(never()).embed(anyString());
		then(productRepository).should(never()).updateEmbedding(any(), any(), anyString());
		assertThat(refreshed).isEmpty();
	}

	@Test
	@DisplayName("저장된 해시가 없으면 임베딩을 만들어 저장한다")
	void embedsWhenNeverEmbedded() {
		Product product = product("이름");
		given(productRepository.findEmbeddingSourceHashes(List.of(product.getId()))).willReturn(Map.of());
		given(embeddingClient.embed(anyString())).willReturn(new float[] {0.1f, 0.2f});

		Map<UUID, float[]> refreshed = updater.refreshAndGet(List.of(product));

		then(embeddingClient).should(times(1)).embed(EmbeddingSource.of(product).text());
		then(productRepository).should()
			.updateEmbedding(product.getId(), new float[] {0.1f, 0.2f}, EmbeddingSource.of(product).hash());
		assertThat(refreshed).containsOnlyKeys(product.getId());
		assertThat(refreshed.get(product.getId())).containsExactly(0.1f, 0.2f);
	}

	@Test
	@DisplayName("텍스트가 바뀌어 해시가 달라지면 다시 만든다")
	void embedsWhenHashChanged() {
		Product product = product("이름");
		given(productRepository.findEmbeddingSourceHashes(List.of(product.getId())))
			.willReturn(Map.of(product.getId(), "옛날해시"));
		given(embeddingClient.embed(anyString())).willReturn(new float[] {0.3f});

		updater.refreshAndGet(List.of(product));

		then(embeddingClient).should(times(1)).embed(anyString());
	}

	@Test
	@DisplayName("임베딩 생성이 실패하면 해시를 저장하지 않아 다음 사이클에 다시 시도되고, warn 로그를 남긴다")
	void skipsSaveWhenEmbeddingFails() {
		Product product = product("이름");
		given(productRepository.findEmbeddingSourceHashes(List.of(product.getId()))).willReturn(Map.of());
		given(embeddingClient.embed(anyString())).willReturn(null);

		updater.refreshAndGet(List.of(product));

		then(productRepository).should(never()).updateEmbedding(any(), any(), anyString());
		// 이 검증이 없으면 임베딩 생성 실패가 검색 파이프라인 어디에도 흔적을 안 남기고 사라진다.
		assertThat(logAppender.list).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains(product.getId().toString());
		});
	}

	@Test
	@DisplayName("한 상품이 실패해도 나머지는 계속 처리한다")
	void continuesAfterFailure() {
		Product failing = product("실패");
		Product succeeding = product("성공");
		given(productRepository.findEmbeddingSourceHashes(any())).willReturn(Map.of());
		given(embeddingClient.embed(EmbeddingSource.of(failing).text())).willReturn(null);
		given(embeddingClient.embed(EmbeddingSource.of(succeeding).text())).willReturn(new float[] {0.5f});

		Map<UUID, float[]> refreshed = updater.refreshAndGet(List.of(failing, succeeding));

		then(productRepository).should()
			.updateEmbedding(succeeding.getId(), new float[] {0.5f}, EmbeddingSource.of(succeeding).hash());
		assertThat(refreshed).containsOnlyKeys(succeeding.getId());
	}

	@Test
	@DisplayName("대상이 없으면 조회조차 하지 않는다")
	void doesNothingWhenEmpty() {
		updater.refreshAndGet(List.of());

		then(productRepository).shouldHaveNoInteractions();
		then(embeddingClient).shouldHaveNoInteractions();
	}

	/** 원문 내용은 이 테스트와 무관하다 — 해시 비교만 본다. */
	private Product product(String name) {
		return Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent(name, 1000));
	}
}
