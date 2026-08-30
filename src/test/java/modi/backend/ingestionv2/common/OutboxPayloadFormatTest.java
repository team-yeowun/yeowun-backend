package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;

/**
 * payload 직렬화 형식을 문자열 그대로 고정한다.
 *
 * <ul>
 *   <li>아웃박스 행을 앱 밖에서 만들어 넣는 경로(부하 실험 적재기)가 이 형식을 템플릿으로 쓴다</li>
 *   <li>필드 이름이나 시각 표기가 한 글자만 달라져도 적재한 행 전부가 발행 시점에 해석 실패로 떨어지는데,
 *       그 사실은 적재가 끝난 뒤에야 드러난다 - 형식 변경을 여기서 먼저 깨지게 둔다</li>
 * </ul>
 */
class OutboxPayloadFormatTest {

	@Test
	@DisplayName("payload JSON은 좌표 네 필드를 이 순서·이 표기로 담는다")
	void payload_JSON_형식이_고정되어_있다() {
		// given
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 30, 15, 4, 5, 123_456_000);

		// when
		String json = OutboxPayload.of(IngestionEventType.COLLECTED, "lab-1", occurredAt).toJson();

		// then
		assertThat(json).isEqualTo("{\"aggregateType\":\"COLLECTION\",\"aggregateId\":\"lab-1\","
				+ "\"eventType\":\"COLLECTED\",\"occurredAt\":\"2026-08-30T15:04:05.123456\"}");
	}

	@Test
	@DisplayName("적재기가 쓰는 템플릿을 그대로 해석할 수 있다")
	void 적재기_템플릿을_그대로_해석한다() {
		// given 부하 실험 적재 SQL 이 만드는 문자열과 같은 모양
		String seeded = "{\"aggregateType\":\"COLLECTION\",\"aggregateId\":\"lab-42\","
				+ "\"eventType\":\"COLLECTED\",\"occurredAt\":\"2026-08-30T15:04:05.123456\"}";

		// when
		OutboxPayload payload = OutboxPayload.fromJson(seeded);

		// then
		assertThat(payload.eventType()).isEqualTo(IngestionEventType.COLLECTED);
		assertThat(payload.aggregateId()).isEqualTo("lab-42");
	}
}
