package modi.backend.ingestionv2.common.queue;

import java.util.Map;

import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.support.error.CoreException;

/**
 * 대기열 레코드의 형식 - payload 필드 하나.
 *
 * <ul>
 *   <li>컨슈머는 원장이 아니라 이 payload를 읽어 처리 대상을 안다</li>
 *   <li>아웃박스 행 번호를 싣지 않음 - 배달 계층의 식별자가 이벤트에 섞이지 않게</li>
 *   <li>해석 실패는 예외 - 소비 쪽이 격리로 넘길 근거</li>
 *   <li>필드 이름 문자열은 이 클래스에만 존재</li>
 * </ul>
 */
public record EventRecord(OutboxPayload payload) {

	private static final String FIELD_PAYLOAD = "payload";

	public static EventRecord of(OutboxPayload payload) {
		return new EventRecord(payload);
	}

	public IngestionEventType type() {
		return payload.eventType();
	}

	public String aggregateId() {
		return payload.aggregateId();
	}

	public Map<String, String> toFields() {
		return Map.of(FIELD_PAYLOAD, payload.toJson());
	}

	/** 해석 여부와 무관한 payload 원문 - 격리 기록이 남길 값. 필드가 없으면 null. */
	public static String rawPayloadOf(Map<String, String> fields) {
		return fields.get(FIELD_PAYLOAD);
	}

	public static EventRecord from(Map<String, String> fields) {
		String json = fields.get(FIELD_PAYLOAD);
		if (json == null) {
			throw new CoreException(IngestionErrorCode.EVENT_RECORD_MALFORMED, "payload 필드가 없습니다.");
		}
		return new EventRecord(OutboxPayload.fromJson(json));
	}
}
