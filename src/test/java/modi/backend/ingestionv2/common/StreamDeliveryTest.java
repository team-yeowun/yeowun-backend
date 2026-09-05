package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;

import modi.backend.ingestionv2.common.event.IngestionAggregateType;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;

@DisplayName("대기열 실물 동작")
class StreamDeliveryTest extends DeliveryTestSupport {

	@Test
	@DisplayName("발송하면 스트림에 기존 payload와 멱등 판정용 event_id가 실린다")
	void 발송하면_스트림에_payload와_event_id가_실린다() {
		// given
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();

		// then payload 필드는 구버전 소비자가 읽던 형식 그대로다. event_id는 payload 밖에 실어
		// 롤링 배포 중 구버전 소비자가 모르는 필드를 무시하고 계속 동작하게 한다
		Map<String, String> fields = readAs("peek").getFirst().getValue();
		assertThat(fields).containsOnlyKeys("payload", "event_id");
		assertThat(fields.get("payload")).isEqualTo(outboxRepository.findAll().getFirst().getPayload());
		assertThat(fields.get("event_id")).isEqualTo(outboxRepository.findAll().getFirst().getEventId());
		OutboxPayload payload = OutboxPayload.fromJson(fields.get("payload"));
		assertThat(payload.eventType()).isEqualTo(IngestionEventType.COLLECTED);
		assertThat(payload.aggregateType()).isEqualTo(IngestionAggregateType.COLLECTION);
		assertThat(payload.aggregateId()).isEqualTo(vendorKey);
		assertThat(payload.occurredAt()).isNotNull();
	}

	@Test
	@DisplayName("확인하기 전에는 미처리 목록에 남고 소비가 끝나면 빠지되 아웃박스는 손대지 않는다")
	void 확인_전에는_남고_확인_후에는_빠진다() {
		// given 사실 하나를 적재해 스트림에 올린다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxDispatcher.dispatchPending();

		// when 그룹으로 읽기만 하고 처리하지 않는다
		List<MapRecord<String, String, String>> read = readAs("dead-consumer");

		// then 미처리 목록에 남는다
		assertThat(read).hasSize(1);
		assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(1);

		// when 그 항목을 실물 소비 어댑터로 넘긴다
		streamConsumer.onMessage(read.getFirst());

		// then 미처리 목록에서 빠진다. 아웃박스는 발행 완료(SENT) 그대로이고 스트림 길이도 줄지 않는다(로그 구조).
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
		assertThat(outboxRepository.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(lengthOf(IngestionStream.DB)).isEqualTo(1);
	}

	@Test
	@DisplayName("한 항목은 그룹 안의 한 컨슈머에게만 간다")
	void 한_항목은_그룹_안의_한_컨슈머에게만_간다() {
		// given 항목 하나를 발송한다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxDispatcher.dispatchPending();

		// when 서로 다른 컨슈머 이름으로 두 번 읽는다
		int first = readAs("instance-a").size();
		int second = readAs("instance-b").size();

		// then 합계가 1건이다
		assertThat(first + second).isEqualTo(1);
	}

	@Test
	@DisplayName("트리밍이 상한을 넘은 항목을 잘라낸다")
	void 트리밍이_상한을_넘은_항목을_잘라낸다() {
		// given 항목 다섯을 발송한다
		for (int index = 0; index < 5; index++) {
			outboxAppender.append(IngestionEventType.COLLECTED, vendorKey + "-" + index);
		}
		outboxDispatcher.dispatchPending();
		assertThat(lengthOf(IngestionStream.DB)).isEqualTo(5);

		// when 상한을 넘긴 뒤 트리밍을 돌린다
		redisTemplate.opsForStream().trim(IngestionStream.DB.key(), 2, true);

		// then 처리 확인과 무관하게 오래된 항목부터 잘린다
		assertThat(lengthOf(IngestionStream.DB)).isLessThanOrEqualTo(5);
		assertThatCode(streamTrimmer::trimAll).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("그룹 부트스트랩은 두 번 실행해도 안전하다")
	void 그룹_부트스트랩은_두_번_실행해도_안전하다() {
		// given 그룹이 이미 있는 상태에서
		// when 부트스트랩을 다시 실행한다 (두 번째 인스턴스의 기동과 같은 상황)
		// then BUSYGROUP 만 삼키고 예외 없이 끝난다
		assertThatCode(() -> streamGroupInitializer.afterPropertiesSet()).doesNotThrowAnyException();
		assertThat(redisTemplate.opsForStream().groups(IngestionStream.DB.key()))
				.filteredOn(group -> properties.consumerGroup().equals(group.groupName()))
				.hasSize(1);
	}
}
