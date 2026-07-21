package modi.backend.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import modi.backend.ingestion.properties.CatalogFetchProperties;

/**
 * 전시 목록 수집 <b>요청 정책</b> 바인딩.
 * <p>
 * 벤더 클라이언트 설정({@link KoreaCultureInformationClientConfig})과 일부러 갈라 둔다 — 이 정책은
 * "원천에 무엇을 얼마나 요청할지"라 특정 벤더에 매이지 않는다. 원천이 바뀌어도 정책은 그대로다.
 */
@Configuration
@EnableConfigurationProperties(CatalogFetchProperties.class)
public class CatalogFetchConfig {
}
