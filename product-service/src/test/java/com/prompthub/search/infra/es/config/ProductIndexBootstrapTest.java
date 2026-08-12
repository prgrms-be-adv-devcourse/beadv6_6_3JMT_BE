package com.prompthub.search.infra.es.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.PutAliasResponse;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 실제 ES 없이 alias/index 상태 조합별 분기를 검증한다. rogue index·alias 충돌은 겪었던 장애라
 * 실제 ES로 재현하기보다, 응답을 직접 구성해 각 상태 분기가 의도대로 막히는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductIndexBootstrapTest {

	@Mock
	private ElasticsearchClient client;

	@Mock
	private ElasticsearchIndicesClient indicesClient;

	private ProductIndexBootstrap bootstrap;

	@BeforeEach
	void setUp() {
		given(client.indices()).willReturn(indicesClient);
		bootstrap = new ProductIndexBootstrap(client);
	}

	@Test
	@DisplayName("alias가 정확히 기대 index를 가리키면 mapping만 검증하고 정상 진행한다")
	void aliasPointsToExpectedIndex_proceeds() throws Exception {
		given(indicesClient.existsAlias(any(Function.class))).willReturn(new BooleanResponse(true));
		given(indicesClient.getAlias(any(Function.class))).willReturn(aliasResponseFor("products-v1"));
		given(indicesClient.getMapping(any(Function.class))).willReturn(mappingResponseWithDims(1536));

		assertThatCode(() -> bootstrap.createIndexIfMissing()).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("alias가 기대와 다른 index를 가리키면 자동 재연결 없이 시작을 막는다")
	void aliasPointsElsewhere_failsStartup() throws Exception {
		given(indicesClient.existsAlias(any(Function.class))).willReturn(new BooleanResponse(true));
		given(indicesClient.getAlias(any(Function.class))).willReturn(aliasResponseFor("products-v0-legacy"));

		assertThatThrownBy(() -> bootstrap.createIndexIfMissing())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("기대하지 않는 index");
	}

	@Test
	@DisplayName("alias와 index 모두 없으면 mapping 리소스로 index를 만들고 alias를 연결한다")
	void aliasAndIndexBothMissing_createsIndexAndAttachesAlias() throws Exception {
		given(indicesClient.existsAlias(any(Function.class))).willReturn(new BooleanResponse(false));
		// 첫 번째는 "products"(rogue 여부) 확인, 두 번째는 "products-v1"(존재 여부) 확인 — 둘 다 없다.
		given(indicesClient.exists(any(Function.class))).willReturn(new BooleanResponse(false), new BooleanResponse(false));
		given(indicesClient.create(any(Function.class)))
			.willReturn(CreateIndexResponse.of(b -> b.acknowledged(true).shardsAcknowledged(true).index("products-v1")));
		given(indicesClient.putAlias(any(Function.class)))
			.willReturn(PutAliasResponse.of(b -> b.acknowledged(true)));

		assertThatCode(() -> bootstrap.createIndexIfMissing()).doesNotThrowAnyException();

		then(indicesClient).should().create(any(Function.class));
		then(indicesClient).should().putAlias(any(Function.class));
		// 이미 존재할 때의 mapping 검증(getMapping)은 생성 경로에서 필요 없다 — 방금 만든 mapping을 신뢰한다.
		then(indicesClient).should(org.mockito.Mockito.never()).getMapping(any(Function.class));
	}

	@Test
	@DisplayName("products라는 실제 index가 alias 이름을 점유하면 자동 삭제 없이 시작을 막는다")
	void realIndexOccupiesAliasName_failsStartup() throws Exception {
		given(indicesClient.existsAlias(any(Function.class))).willReturn(new BooleanResponse(false));
		given(indicesClient.exists(any(Function.class))).willReturn(new BooleanResponse(true));

		assertThatThrownBy(() -> bootstrap.createIndexIfMissing())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("alias 이름을 점유");
	}

	@Test
	@DisplayName("products-v1은 있고 alias만 없으면 mapping 검증 후 alias만 다시 연결한다")
	void indexExistsWithoutAlias_reattachesAlias() throws Exception {
		given(indicesClient.existsAlias(any(Function.class))).willReturn(new BooleanResponse(false));
		// 호출 순서대로 첫 번째는 "products"(rogue 여부), 두 번째는 "products-v1"(존재 여부) 확인이다.
		given(indicesClient.exists(any(Function.class))).willReturn(new BooleanResponse(false), new BooleanResponse(true));
		given(indicesClient.getMapping(any(Function.class))).willReturn(mappingResponseWithDims(1536));

		assertThatCode(() -> bootstrap.createIndexIfMissing()).doesNotThrowAnyException();

		then(indicesClient).should().putAlias(any(Function.class));
	}

	@Test
	@DisplayName("embedding dense_vector dims가 기대와 다르면 시작을 막는다")
	void embeddingDimensionMismatch_failsStartup() throws Exception {
		given(indicesClient.existsAlias(any(Function.class))).willReturn(new BooleanResponse(true));
		given(indicesClient.getAlias(any(Function.class))).willReturn(aliasResponseFor("products-v1"));
		given(indicesClient.getMapping(any(Function.class))).willReturn(mappingResponseWithDims(768));

		assertThatThrownBy(() -> bootstrap.createIndexIfMissing())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("dims");
	}

	private GetAliasResponse aliasResponseFor(String indexName) {
		return GetAliasResponse.of(b -> b.aliases(Map.of(indexName, IndexAliases.of(a -> a.aliases(Map.of())))));
	}

	private GetMappingResponse mappingResponseWithDims(int dims) {
		TypeMapping mapping = TypeMapping.of(t -> t
			.properties("familyRootId", p -> p.keyword(k -> k))
			.properties("productId", p -> p.keyword(k -> k))
			.properties("amount", p -> p.integer(i -> i))
			.properties("embedding", p -> p.denseVector(d -> d.dims(dims))));
		return GetMappingResponse.of(b -> b.mappings(
			Map.of("products-v1", IndexMappingRecord.of(r -> r.mappings(mapping)))));
	}
}
