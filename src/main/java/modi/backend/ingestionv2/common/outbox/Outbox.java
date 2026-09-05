package modi.backend.ingestionv2.common.outbox;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.event.IngestionAggregateType;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;

/**
 * 발행된 사실의 기록(outbox_event 모양).
 *
 * <ul>
 *   <li>도메인 트랜잭션에 합류해 상태 변경과 함께 커밋 - 유실 방지의 본체</li>
 *   <li>aggregate_type + aggregate_id 가 사실의 좌표, payload 가 컨슈머가 읽는 이벤트 데이터</li>
 *   <li>retry_count 는 발행 시도 횟수 - 소비 재시도 횟수는 도메인 애그리거트가 소유</li>
 *   <li>상한을 넘긴 발행 실패는 FAILED - 관리자가 확인하고 수동으로 되돌린다</li>
 * </ul>
 */
@Entity(name = "IngestionV2Outbox")
@Table(name = "ingestion_outbox", indexes = {
		@Index(name = "idx_ingestion_outbox_status_created", columnList = "status, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outbox {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 호환 배포 동안 기존 행은 null일 수 있고, 새 Outbox는 생성자에서 항상 UUID를 기록한다. */
	@Column(name = "event_id", length = 36)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "aggregate_type", nullable = false, length = 100)
	private IngestionAggregateType aggregateType;

	@Column(name = "aggregate_id", nullable = false, length = 100)
	private String aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 100)
	private IngestionEventType eventType;

	@Lob
	@Column(name = "payload", nullable = false, columnDefinition = "text")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OutboxStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	private Outbox(OutboxPayload payload) {
		this.eventId = payload.eventId();
		this.aggregateType = payload.aggregateType();
		this.aggregateId = payload.aggregateId();
		this.eventType = payload.eventType();
		this.payload = payload.toJson();
		this.status = OutboxStatus.PENDING;
		this.retryCount = 0;
		this.createdAt = payload.occurredAt();
	}

	/** 적재 - 항상 PENDING으로 태어난다. 호출자의 도메인 트랜잭션에 합류한다. */
	public static Outbox pending(IngestionEventType type, String aggregateId) {
		return new Outbox(OutboxPayload.of(type, aggregateId, IngestionClock.now()));
	}

	/** 고정 eventId를 가진 payload를 새 Outbox 행으로 보존해야 하는 복구·검증 경로. */
	public static Outbox pending(OutboxPayload payload) {
		if (payload.eventId() == null) {
			throw new IllegalArgumentException("새 Outbox에는 eventId가 필요합니다.");
		}
		return new Outbox(payload);
	}

	/** 컨슈머에게 실어 보낼 이벤트 데이터. */
	public OutboxPayload toPayload() {
		return OutboxPayload.fromJson(this.payload).withEventId(this.eventId);
	}

	/** 발행 완료 - XADD 응답을 받은 시점의 표시. 이 시도도 한 번으로 센다. */
	public void markSent(LocalDateTime now) {
		this.status = OutboxStatus.SENT;
		this.sentAt = now;
		this.retryCount += 1;
	}

	/** 배정 스트림이 없는 사실의 종결 - 발행 없이 곧바로 SENT. 시도 횟수는 세지 않는다. */
	public void markSentWithoutStream(LocalDateTime now) {
		this.status = OutboxStatus.SENT;
		this.sentAt = now;
	}

	/** 발행 실패 - 시도를 세고, 상한에 닿으면 FAILED로 걷어낸다. 아니면 PENDING으로 남아 다음 틱이 다시 집는다. */
	public void markPublishFailed(int maxAttempts) {
		this.retryCount += 1;
		if (this.retryCount >= maxAttempts) {
			this.status = OutboxStatus.FAILED;
		}
	}

	/** 관리자 재시도 - FAILED 행을 PENDING으로 되돌리고 시도 횟수를 0에서 다시 센다. */
	public void retry() {
		if (this.status != OutboxStatus.FAILED) {
			throw new IllegalStateException("발행 실패 상태가 아닌 행은 되돌릴 수 없습니다. status=" + this.status);
		}
		this.status = OutboxStatus.PENDING;
		this.retryCount = 0;
	}

	public boolean isFailed() {
		return this.status == OutboxStatus.FAILED;
	}
}
