package modi.backend.ingestionv2.common.event;

import java.time.LocalDateTime;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.support.error.CoreException;

/**
 * 아웃박스 payload 컬럼과 대기열 레코드가 공유하는 이벤트 데이터.
 *
 * <ul>
 *   <li>발행 쪽이 직렬화해 payload에 넣고, 컨슈머는 원장이 아니라 이 값을 읽어 처리 대상을 안다</li>
 *   <li>담는 값은 사실의 좌표(애그리거트 종류·식별자·이벤트 종류·발생 시각) - 벤더 원본 값은 원장에 있다</li>
 *   <li>해석 실패는 예외 - 소비 쪽이 격리로 넘길 근거</li>
 * </ul>
 */
public record OutboxPayload(
		IngestionAggregateType aggregateType,
		String aggregateId,
		IngestionEventType eventType,
		LocalDateTime occurredAt) {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	public OutboxPayload {
		if (aggregateType == null || eventType == null || aggregateId == null || aggregateId.isBlank()) {
			throw new CoreException(IngestionErrorCode.EVENT_RECORD_MALFORMED, "이벤트 데이터의 좌표가 비어 있습니다.");
		}
	}

	public static OutboxPayload of(IngestionEventType eventType, String aggregateId, LocalDateTime occurredAt) {
		return new OutboxPayload(eventType.aggregateType(), aggregateId, eventType, occurredAt);
	}

	public String toJson() {
		return MAPPER.writeValueAsString(this);
	}

	public static OutboxPayload fromJson(String json) {
		if (json == null || json.isBlank()) {
			throw new CoreException(IngestionErrorCode.EVENT_RECORD_MALFORMED, "이벤트 데이터가 비어 있습니다.");
		}
		try {
			return MAPPER.readValue(json, OutboxPayload.class);
		} catch (JacksonException | IllegalArgumentException malformed) {
			throw new CoreException(IngestionErrorCode.EVENT_RECORD_MALFORMED,
					"이벤트 데이터를 해석할 수 없습니다. payload=" + json, malformed);
		}
	}
}
