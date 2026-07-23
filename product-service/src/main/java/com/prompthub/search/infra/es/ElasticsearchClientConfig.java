package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 9.x 클라이언트는 7.x/8.x의 org.elasticsearch.client.RestClient/RestClientTransport
 * 대신, httpclient5 기반의 Rest5Client/Rest5ClientTransport를 쓴다(elasticsearch-java
 * 9.0.2 실제 클래스패스 확인 결과). httpclient5의 기본 async 클라이언트가 응답
 * gzip 압축을 요청·수신하는데, 실행 중 이 조합에서 "Not in GZIP format" 오류가
 * 재현되어(로컬 ES로 직접 검증) content compression 자체를 비활성화한 커스텀
 * HttpAsyncClient를 주입한다.
 */
@Configuration
public class ElasticsearchClientConfig {

	@Value("${elasticsearch.uris}")
	private String uris;

	@Bean(destroyMethod = "close")
	public CloseableHttpAsyncClient elasticsearchHttpAsyncClient() {
		CloseableHttpAsyncClient client = HttpAsyncClients.custom().disableContentCompression().build();
		client.start();
		return client;
	}

	@Bean(destroyMethod = "close")
	public Rest5Client rest5Client(CloseableHttpAsyncClient elasticsearchHttpAsyncClient) throws java.net.URISyntaxException {
		return Rest5Client.builder(HttpHost.create(uris))
			.setHttpClient(elasticsearchHttpAsyncClient)
			.build();
	}

	@Bean
	public ElasticsearchTransport elasticsearchTransport(Rest5Client rest5Client) {
		return new Rest5ClientTransport(rest5Client, new JacksonJsonpMapper());
	}

	@Bean
	public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
		return new ElasticsearchClient(transport);
	}
}
