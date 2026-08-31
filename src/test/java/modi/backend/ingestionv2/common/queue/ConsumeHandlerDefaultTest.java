package modi.backend.ingestionv2.common.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.common.event.IngestionEventType;

/**
 * 소비 핸들러 스위치의 기본값을 고정한다.
 *
 * <ul>
 *   <li>스위치를 주지 않은 컨텍스트가 곧 운영 배선이라 여기서 REAL 이 아니면 프로덕션 동작이 바뀐 것</li>
 *   <li>부하 실험 전용 빈이 기본 배선에 섞여 들어오지 않는다는 것도 함께 고정</li>
 * </ul>
 */
@DisplayName("소비 핸들러 기본값")
class ConsumeHandlerDefaultTest extends IngestionTestSupport {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	@DisplayName("스위치를 주지 않으면 REAL 이고 스텁 지연은 0이다")
	void 스위치를_주지_않으면_REAL_이다() {
		// given 아무 프로퍼티도 주지 않은 기본 컨텍스트

		// when
		ConsumeHandler consumeHandler = properties.consumeHandler();

		// then
		assertThat(consumeHandler).isEqualTo(ConsumeHandler.REAL);
		assertThat(properties.stubLatencyMs()).isZero();
	}

	@Test
	@DisplayName("기본 배선에는 실험용 스텁 빈이 없고 도메인 핸들러가 이벤트를 맡는다")
	void 기본_배선에는_스텁_빈이_없다() {
		// given 기본 컨텍스트

		// when
		IngestionEventHandler handler = eventRouter.route(IngestionEventType.DETAIL_READY).orElseThrow();

		// then 스텁이 살아 있으면 supports 가 전부 참이라 라우터의 findFirst 가 도메인 핸들러를 가릴 수 있다
		assertThat(applicationContext.getBeansOfType(StubEventHandler.class)).isEmpty();
		assertThat(handler).isNotInstanceOf(StubEventHandler.class);
	}
}
