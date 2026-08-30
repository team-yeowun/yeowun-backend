package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;

@DisplayName("라우팅")
class EventRoutingTest extends DeliveryTestSupport {

	@Test
	@DisplayName("이벤트는 supports가 참인 핸들러에게만 넘어간다")
	void 이벤트는_supports가_참인_핸들러에게만_넘어간다() {
		// given COLLECTED 를 맡는 핸들러가 있다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		drainAll();

		// then
		assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
	}

	@Test
	@DisplayName("맡지 않는 핸들러는 호출되지 않는다")
	void 맡지_않는_핸들러는_호출되지_않는다() {
		// given COLLECTED 와 ENRICHED 를 각각 맡는 핸들러 둘
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		drainAll();

		// then 한 이벤트가 여러 도메인을 깨우면 처리 확인 시점과 재처리 범위가 무너진다
		assertThat(recordingEnrichedHandler.received()).isEmpty();
	}

	@Test
	@DisplayName("맡는 곳이 없으면 예외 없이 그대로 종결된다")
	void 맡는_곳이_없으면_예외_없이_그대로_종결된다() {
		// given 아무도 맡지 않는 종류(기록용 핸들러 셋 중 어느 것도 INSPECTED 를 맡지 않는다)
		outboxAppender.append(IngestionEventType.INSPECTED, vendorKey);

		// when
		drainAll();

		// then 소비자 없음은 오류가 아니라 종결이다. 격리 유입률 알람이 잘못 울리지 않고 아웃박스도 그대로다.
		assertThat(outboxRepository.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("핸들러는 원천 키 하나만 받는다")
	void 핸들러는_원천_키_하나만_받는다() {
		// given 원천 키를 지정해 적재한다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		drainAll();

		// then 포트 시그니처가 도메인 데이터의 구조를 드러내지 않는다
		assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
	}
}
