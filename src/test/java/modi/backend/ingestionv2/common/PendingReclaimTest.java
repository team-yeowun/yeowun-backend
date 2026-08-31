package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionStream;

/**
 * 미처리 항목 회수.
 *
 * <p>스위트에서 유일하게 시간을 소모하는 테스트다. 방치 판정이 마지막 전달 이후 경과 시간이고
 * 설정 레코드가 회수 기준의 하한을 1초로 잡으므로 그만큼은 실제로 흘러야 한다.
 * <b>결과를 기다리는 것이 아니라 시각을 앞으로 미는 것이라 간헐적 실패를 만들지 않는다.</b>
 */
@TestPropertySource(properties = {
		"app.ingestion.v2.reclaim-idle-seconds=1"
})
@DisplayName("미처리 항목 회수")
class PendingReclaimTest extends DeliveryTestSupport {

	@Test
	@DisplayName("확인하지 않고 빠져나온 항목을 회수가 다시 실행한다")
	void 확인하지_않고_빠져나온_항목을_회수가_다시_실행한다() throws Exception {
		// given 다른 컨슈머가 읽고 확인하지 않은 채 사라졌다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxDispatcher.dispatchPending();
		readAs("gone-consumer");
		assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(1);
		Thread.sleep(properties.reclaimIdleSeconds() * 1000L + 300L);

		// when
		pendingReclaimer.reclaimAll();

		// then 핸들러가 실행되고 미처리 목록이 빈다
		assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("방치 기준을 넘지 않은 항목은 회수하지 않는다")
	void 방치_기준을_넘지_않은_항목은_회수하지_않는다() {
		// given 방금 전달된 항목
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxDispatcher.dispatchPending();
		readAs("busy-consumer");

		// when 곧바로 회수를 돌린다
		pendingReclaimer.reclaimAll();

		// then 정상 처리 중인 작업을 가로채지 않는다
		assertThat(recordingCollectedHandler.received()).isEmpty();
		assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(1);
	}
}
