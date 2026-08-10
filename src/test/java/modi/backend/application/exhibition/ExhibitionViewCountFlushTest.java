package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.domain.exhibition.catalog.ExhibitionViewCounter;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * 조회수가 <b>조회 경로에서는 Redis에만 쌓이고, 배치가 한 번에 MySQL로 옮기는지</b>를 끝에서 끝까지 확인한다.
 * 이 두 성질이 깨지면 상세 캐시를 얹어도 요청마다 DB 쓰기가 남거나(1번), 인기순 정렬이 얼어붙는다(2번).
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionViewCountFlushTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Container
	@SuppressWarnings("resource")
	private static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	ExhibitionViewCounter viewCounter;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	private Exhibition givenExhibition() {
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "조회수검증관", ExhibitionRegion.SEOUL);
		LocalDate today = LocalDate.now();
		return exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, "view-" + SEQ.getAndIncrement(),
				"조회수 검증 전시", today.minusDays(1), today.plusDays(30), ExhibitionCategory.PAINTING));
	}

	private long storedViewCount(Long exhibitionId) {
		return exhibitionRepository.findById(exhibitionId).orElseThrow().getOurViewCount();
	}

	@Test
	@DisplayName("상세 조회는 MySQL에 쓰지 않는다 — 조회수는 누산되고, 응답에는 반영분 + 누산분이 나간다")
	void 상세조회는_누산만하고_응답에는_합산해서_준다() {
		Exhibition exhibition = givenExhibition();

		ExhibitionResult.Detail detail = exhibitionFacade.getDetail(
				new ExhibitionCriteria.Detail(exhibition.getId(), null));

		assertThat(detail.viewCount()).isEqualTo(1);
		assertThat(storedViewCount(exhibition.getId())).isZero();
	}

	@Test
	@DisplayName("flush는 누산분을 MySQL에 한 번만 더한다 — 두 번 돌아도 조회수가 부풀지 않는다")
	void flush_한번만_더한다() {
		Exhibition exhibition = givenExhibition();
		viewCounter.increase(exhibition.getId());
		viewCounter.increase(exhibition.getId());

		exhibitionFacade.flushViewCounts();
		assertThat(storedViewCount(exhibition.getId())).isEqualTo(2);

		exhibitionFacade.flushViewCounts();
		assertThat(storedViewCount(exhibition.getId())).isEqualTo(2);
	}
}
