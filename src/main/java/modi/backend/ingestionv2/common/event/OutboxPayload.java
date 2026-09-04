package modi.backend.ingestionv2.common.event;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.support.error.CoreException;

/**
 * 아웃박스 payload 컬럼과 대기열 레코드가 공유하는 이벤트 데이터.
 *
 * <ul>
 *   <li>발행 쪽이 직렬화해 payload에 넣고, 컨슈머는 원장이 아니라 이 값을 읽어 처리 대상을 안다</li>
 *   <li>eventId는 Outbox 생성 시 한 번 정하고 같은 행의 재발행에도 유지 - Redis record ID는 전송 시도 식별자일 뿐이다</li>
 *   <li>롤링 배포 중 구버전 소비자와 호환되도록 eventId는 기존 payload JSON에 넣지 않고 Outbox 컬럼과 Stream 필드로 운반한다</li>
 *   <li>배포 전부터 Redis에 남아 있던 레코드는 eventId가 없을 수 있어 null을 허용하고 소비자가 Inbox를 우회한다</li>
 *   <li>해석 실패는 예외 - 소비 쪽이 격리로 넘길 근거</li>
 * </ul>
 */
public record OutboxPayload(
		@JsonIgnore String eventId,
		IngestionAggregateType aggregateType,
		String aggregateId,
		IngestionEventType eventType,
		LocalDateTime occurredAt) {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	public OutboxPayload {
		if (aggregateType == null || eventType == null || aggregateId == null || aggregateId.isBlank()) {
			throw new CoreException(IngestionErrorCode.EVENT_RECORD_MALFORMED, "이벤트 데이터의 좌표가 비어 있습니다.");
		}
		if (eventId != null) {
			try {
				UUID.fromString(eventId);
			} catch (IllegalArgumentException malformed) {
				throw new CoreException(IngestionErrorCode.EVENT_RECORD_MALFORMED,
						"eventId 형식이 UUID가 아닙니다. eventId=" + eventId, malformed);
			}
		}
	}

	public static OutboxPayload of(IngestionEventType eventType, String aggregateId, LocalDateTime occurredAt) {
		return of(UUID.randomUUID().toString(), eventType, aggregateId, occurredAt);
	}

	/** 테스트·복구 도구가 고정 eventId로 같은 사건의 재발행을 재현할 때 사용한다. */
	public static OutboxPayload of(
			String eventId, IngestionEventType eventType, String aggregateId, LocalDateTime occurredAt) {
		return new OutboxPayload(eventId, eventType.aggregateType(), aggregateId, eventType, occurredAt);
	}

	public OutboxPayload withEventId(String stableEventId) {
		return new OutboxPayload(stableEventId, aggregateType, aggregateId, eventType, occurredAt);
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
