package com.prompthub.search.infra.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prompthub.search.infra.external.jina.JinaRerankerProperties;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 9.x 클라이언트는 7.x/8.x의 org.elasticsearch.client.RestClient/RestClientTransport
 * 대신, httpclient5 기반의 Rest5Client/Rest5ClientTransport를 쓴다(elasticsearch-java
 * 9.0.2 실제 클래스패스 확인 결과). httpclient5의 기본 async 클라이언트가 응답
 * gzip 압축을 요청·수신하는데, 실행 중 이 조합에서 "Not in GZIP format" 오류가
 * 재현되어(로컬 ES로 직접 검증) content compression 자체를 비활성화한 커스텀
 * HttpAsyncClient를 주입한다.
 *
 * <p><b>인증·TLS는 설정이 있을 때만 붙인다(#582).</b> 로컬 docker-compose ES는 무인증
 * HTTP이고 배포 ES는 전환 후 인증 HTTPS가 되는데, 같은 코드가 양쪽에서 돌아야 한다.
 * 그래서 설정값이 비어 있으면 지금까지와 똑같이 무인증으로 연결한다 — 이 폴백이 있어야
 * 인증 전환(이슈 3번)과 이 코드 배포를 따로 할 수 있다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({SearchRankingProperties.class, ProductReindexProperties.class,
	JinaRerankerProperties.class})
public class ElasticsearchClientConfig {

	// ponytail: 고정값. ES가 붙는 같은 클러스터 안에서만 쓰는 호출이라 넉넉히 잡음 —
	// 운영에서 지연 분포를 보고 조정이 필요해지면 그때 설정값으로 뺀다.
	private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
	private static final Timeout SOCKET_TIMEOUT = Timeout.ofSeconds(10);

	@Value("${elasticsearch.uris}")
	private String uris;

	@Value("${elasticsearch.username:}")
	private String username;

	@Value("${elasticsearch.password:}")
	private String password;

	/** 자체 서명 인증서를 쓰므로 그 CA를 신뢰해야 한다. PEM 파일 경로. */
	@Value("${elasticsearch.ca-path:}")
	private String caPath;

	@Bean(destroyMethod = "close")
	public CloseableHttpAsyncClient elasticsearchHttpAsyncClient() {
		HttpAsyncClientBuilder builder = HttpAsyncClients.custom().disableContentCompression();

		applyBasicAuth(builder);
		applyConnectionManager(builder);

		CloseableHttpAsyncClient client = builder.build();
		client.start();
		return client;
	}

	/**
	 * 사용자명과 비밀번호가 <b>모두</b> 있을 때만 인증을 건다.
	 *
	 * <p>한쪽만 채워진 설정은 인증을 걸지 않고 경고만 남긴다. 조용히 무인증으로 붙으면
	 * 나중에 401을 보고 원인을 설정이 아니라 서버에서 찾게 된다.
	 */
	private void applyBasicAuth(HttpAsyncClientBuilder builder) {
		if (username.isBlank() && password.isBlank()) {
			return;
		}
		if (username.isBlank() || password.isBlank()) {
			log.warn("Elasticsearch 인증 설정이 한쪽만 채워져 있어 무인증으로 연결합니다. "
				+ "username={}, password={}", mask(username), mask(password));
			return;
		}

		BasicCredentialsProvider credentials = new BasicCredentialsProvider();
		credentials.setCredentials(
			new AuthScope(HttpHost.create(URI.create(uris))),
			new UsernamePasswordCredentials(username, password.toCharArray()));
		builder.setDefaultCredentialsProvider(credentials);
		log.info("Elasticsearch에 basic 인증으로 연결합니다. username={}", username);
	}

	/**
	 * connect·응답 timeout을 건다. CA 경로가 있으면 같은 connection manager에 그 인증서만
	 * 신뢰하는 TLS 전략도 함께 붙인다 — connection manager는 클라이언트당 하나만 설정할 수
	 * 있어서 timeout과 TLS를 따로 붙일 수 없다.
	 *
	 * <p>기본 truststore에 없는 자체 서명 CA이므로 TLS 전략을 안 붙이면 핸드셰이크에서
	 * 끊긴다. 경로가 없으면 JVM 기본 신뢰 저장소를 그대로 쓴다 — HTTP 연결이면 애초에
	 * 쓰이지 않는다.
	 */
	private void applyConnectionManager(HttpAsyncClientBuilder builder) {
		PoolingAsyncClientConnectionManagerBuilder connectionManagerBuilder = PoolingAsyncClientConnectionManagerBuilder.create()
			.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(CONNECT_TIMEOUT)
				.setSocketTimeout(SOCKET_TIMEOUT)
				.build());

		applyCustomCa(connectionManagerBuilder);

		// async 클라이언트는 connection manager를 빌더가 아니라 이쪽에 건다.
		// buildAsync()를 쓴다 — build()는 classic용 반환 타입이라 deprecated다.
		builder.setConnectionManager(connectionManagerBuilder.build());
	}

	private void applyCustomCa(PoolingAsyncClientConnectionManagerBuilder connectionManagerBuilder) {
		if (caPath.isBlank()) {
			return;
		}

		try {
			KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
			trustStore.load(null, null);
			try (InputStream in = Files.newInputStream(Path.of(caPath))) {
				Certificate ca = CertificateFactory.getInstance("X.509").generateCertificate(in);
				trustStore.setCertificateEntry("elasticsearch-ca", ca);
			}

			SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(trustStore, null).build();
			connectionManagerBuilder.setTlsStrategy(ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildAsync());
			log.info("Elasticsearch CA 인증서를 신뢰 목록에 추가했습니다. path={}", caPath);
		} catch (Exception e) {
			// 여기서 삼키면 TLS 검증 없이 붙거나 원인 모를 핸드셰이크 실패로 이어진다.
			// 인증서를 주겠다고 설정해놓고 못 읽는 상태는 기동을 막는 편이 낫다.
			throw new IllegalStateException("Elasticsearch CA 인증서를 읽지 못했습니다. path=" + caPath, e);
		}
	}

	private String mask(String value) {
		return value.isBlank() ? "(비어 있음)" : "(설정됨)";
	}

	@Bean(destroyMethod = "close")
	public Rest5Client rest5Client(CloseableHttpAsyncClient elasticsearchHttpAsyncClient) throws java.net.URISyntaxException {
		return Rest5Client.builder(HttpHost.create(uris))
			.setHttpClient(elasticsearchHttpAsyncClient)
			.build();
	}

	// JacksonJsonpMapper()를 인자 없이 생성하면 내부에서 순정 ObjectMapper를 새로 만들어
	// JavaTimeModule이 빠진 채로 쓰인다 — ProductSearchDocument의 LocalDateTime 필드
	// 직렬화가 항상 실패하므로 JavaTimeModule을 등록한 ObjectMapper를 명시적으로 넘긴다.
	@Bean
	public ElasticsearchTransport elasticsearchTransport(Rest5Client rest5Client) {
		ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return new Rest5ClientTransport(rest5Client, new JacksonJsonpMapper(objectMapper));
	}

	@Bean
	public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
		return new ElasticsearchClient(transport);
	}
}
