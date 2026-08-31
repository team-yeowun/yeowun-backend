package modi.backend.ingestionv2.common.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestionv2.collect.domain.CultureCatalogClient;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailClient;
import modi.backend.ingestionv2.enrich.domain.genre.GenreClassifier;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceHoursClient;

/**
 * 소비 핸들러를 STUB 으로 돌린 배선을 고정한다.
 *
 * <ul>
 *   <li>도메인 핸들러가 빈으로 남아 있지 않다는 것이 핵심 - 남아 있으면 회수·재전달 경로에서 벤더를 부를 여지가 있다</li>
 *   <li>중복 소비 집계가 실제로 그렇게 세어진다는 것도 여기서 고정 - 부하 실험의 주 지표가 이 셋 키에서 나온다</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.ingestion.v2.enabled=true",
		"app.ingestion.v2.auto-delivery=false",
		"app.ingestion.v2.consume-handler=STUB",
		"app.ingestion.v2.stub-latency-ms=0",
		"app.exhibition.enrich.scheduling-enabled=false"
})
@DisplayName("소비 핸들러 STUB")
class ConsumeHandlerStubTest {

	/** 밖으로 나가는 포트는 이 컨텍스트에서도 전부 스텁한다. */
	@MockitoBean private CultureCatalogClient catalogClient;
	@MockitoBean private CultureDetailClient detailClient;
	@MockitoBean private GenreClassifier genreClassifier;
	@MockitoBean private PlaceHoursClient placeHoursClient;

	@Autowired private ApplicationContext applicationContext;
	@Autowired private IngestionEventRouter eventRouter;
	@Autowired private IngestionProperties properties;
	@Autowired private StringRedisTemplate redisTemplate;

	@BeforeEach
	void clearLabKeys() {
		redisTemplate.delete(List.of(StubEventHandler.CONSUMED_IDS_KEY, StubEventHandler.CONSUMED_DUP_KEY,
				StubEventHandler.CONSUMED_COUNT_KEY));
	}

	@Test
	@DisplayName("STUB 이면 도메인 핸들러 빈이 하나도 없고 라우터가 스텁을 고른다")
	void STUB_이면_도메인_핸들러_빈이_없다() {
		// given consume-handler=STUB 컨텍스트

		// when
		Map<String, IngestionEventHandler> handlers = applicationContext.getBeansOfType(IngestionEventHandler.class);

		// then 유료 호출 0건의 근거는 "부르지 않았다"가 아니라 "부를 빈이 없다"여야 한다
		assertThat(handlers.values()).hasSize(1).allSatisfy(handler ->
				assertThat(handler).isInstanceOf(StubEventHandler.class));
		for (IngestionEventType type : IngestionEventType.values()) {
			assertThat(eventRouter.route(type)).get().isInstanceOf(StubEventHandler.class);
		}
		assertThat(properties.consumeHandler()).isEqualTo(ConsumeHandler.STUB);
	}

	@Test
	@DisplayName("같은 원천 키가 두 번 오면 중복으로 세고 고유 집합은 늘지 않는다")
	void 같은_원천_키가_두_번_오면_중복으로_센다() {
		// given
		IngestionEventHandler stub = eventRouter.route(IngestionEventType.DETAIL_READY).orElseThrow();

		// when 같은 키를 두 번, 다른 키를 한 번
		stub.handle("lab-1-1-1");
		stub.handle("lab-1-1-1");
		stub.handle("lab-1-1-2");

		// then
		assertThat(redisTemplate.opsForSet().size(StubEventHandler.CONSUMED_IDS_KEY)).isEqualTo(2L);
		assertThat(redisTemplate.opsForValue().get(StubEventHandler.CONSUMED_DUP_KEY)).isEqualTo("1");
		assertThat(redisTemplate.opsForValue().get(StubEventHandler.CONSUMED_COUNT_KEY)).isEqualTo("3");
	}
}
