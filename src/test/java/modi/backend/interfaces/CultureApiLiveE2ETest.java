package modi.backend.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.port.CatalogPageStop;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * 실제 공공데이터 전시 API를 호출하는 E2E(ai-driven-tdd-development-skill 권장 — 실제 API 확인).
 * 인증키(CULTURE_API_KEY)가 환경변수로 있을 때만 실행된다. 없으면 자동 스킵(CI 그린 유지).
 * 실행 예: {@code CULTURE_API_KEY=발급키 ./gradlew test --tests '*CultureApiLiveE2ETest'}
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CULTURE_API_KEY", matches = ".+")
class CultureApiLiveE2ETest {

	@Autowired
	ExhibitionCatalogClient catalogClient;

	@Test
	@DisplayName("실제 API 호출 — 전시 수집 데이터가 비어 있지 않고 필수 필드(id·title)를 갖는다")
	void 실제_수집() {
		List<CatalogExhibitionData> data = catalogClient.fetchAll(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 100, 500), CatalogPageStop.never()).items();

		assertThat(data).isNotEmpty();
		assertThat(data).allMatch(CatalogExhibitionData::isPersistable);
	}
}
