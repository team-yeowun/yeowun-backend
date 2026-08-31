package modi.backend.ingestionv2.common.deadletter;

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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.event.IngestionAggregateType;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;

/**
 * 반복 실패로 정상 흐름에서 걷어낸 항목(표준 DLQ 컬럼 구성).
 *
 * <ul>
 *   <li>좌표 어휘는 아웃박스와 같음(aggregate_type·aggregate_id·event_type·payload) - 되돌려 보낼 때 그대로 적재</li>
 *   <li>원인은 두 층 - error_message(요약)와 stack_trace(상세), failed_step 이 난 지점</li>
 *   <li>retry_count 는 격리 전까지 처리를 시도한 횟수 - 상한 소진은 max-attempts, 해석 불가는 0</li>
 *   <li>status 가 관리 수명(PENDING → REPLAYED / IGNORED), resolved_at 이 그 시각</li>
 *   <li>해석 불가 레코드는 좌표 셋이 비어 있고 payload 원문만 남는다</li>
 *   <li>version 은 재주입 경합을 막는 자리 - 관리자 화면에서 같은 행에 요청이 겹쳐도 상태 전이는 한 번만 성립한다</li>
 * </ul>
 */
@Entity
@Table(name = "ingestion_dead_letter", indexes = {
		@Index(name = "idx_ingestion_dead_letter_status_failed_at", columnList = "status, failed_at"),
		@Index(name = "idx_ingestion_dead_letter_aggregate_id", columnList = "aggregate_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetter {

	private static final int MAX_ERROR_LENGTH = 1000;
	private static final int MAX_STEP_LENGTH = 100;

	/** 해석 실패 지점의 이름. 핸들러가 없으므로 고정 문자열이다. */
	public static final String STEP_DECODE = "DECODE";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "aggregate_type", length = 100)
	private IngestionAggregateType aggregateType;

	@Column(name = "aggregate_id", length = 100)
	private String aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", length = 100)
	private IngestionEventType eventType;

	@Lob
	@Column(name = "payload", columnDefinition = "text")
	private String payload;

	@Column(name = "stream_key", nullable = false, length = 64)
	private String streamKey;

	@Column(name = "record_id", nullable = false, length = 64)
	private String recordId;

	@Column(name = "error_message", length = MAX_ERROR_LENGTH)
	private String errorMessage;

	@Lob
	@Column(name = "stack_trace", columnDefinition = "text")
	private String stackTrace;

	@Column(name = "failed_step", length = MAX_STEP_LENGTH)
	private String failedStep;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "failed_at", nullable = false, updatable = false)
	private LocalDateTime failedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private DeadLetterStatus status;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	private DeadLetter(IngestionAggregateType aggregateType, String aggregateId, IngestionEventType eventType,
			String payload, String streamKey, String recordId, Failure failure, int retryCount) {
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.streamKey = streamKey;
		this.recordId = recordId;
		this.errorMessage = truncate(failure.message(), MAX_ERROR_LENGTH);
		this.stackTrace = failure.stackTrace();
		this.failedStep = truncate(failure.step(), MAX_STEP_LENGTH);
		this.retryCount = retryCount;
		this.failedAt = IngestionClock.now();
		this.status = DeadLetterStatus.PENDING;
	}

	/** 재시도 상한을 넘긴 항목의 격리 - 레코드의 payload 좌표를 그대로 옮긴다. */
	public static DeadLetter of(OutboxPayload payload, String streamKey, String recordId, Failure failure,
			int retryCount) {
		return new DeadLetter(payload.aggregateType(), payload.aggregateId(), payload.eventType(), payload.toJson(),
				streamKey, recordId, failure, retryCount);
	}

	/** 해석할 수 없는 레코드의 격리 - 좌표는 알 수 없고 payload 원문만 남긴다. 시도 횟수는 0. */
	public static DeadLetter malformed(String streamKey, String recordId, String rawPayload, Failure failure) {
		return new DeadLetter(null, null, null, rawPayload, streamKey, recordId, failure, 0);
	}

	/** 관리자 재발행 - 되돌려 보낸 사실을 남겨 같은 행이 두 번 흘러가지 않게 한다. */
	public void markReplayed(LocalDateTime now) {
		this.status = DeadLetterStatus.REPLAYED;
		this.resolvedAt = now;
	}

	/** 관리자 무시 - 처리하지 않기로 한 사실을 남긴다. */
	public void markIgnored(LocalDateTime now) {
		this.status = DeadLetterStatus.IGNORED;
		this.resolvedAt = now;
	}

	public boolean isPending() {
		return this.status == DeadLetterStatus.PENDING;
	}

	/** 되돌려 보낼 좌표가 있는지 - 해석 불가 레코드는 없다. */
	public boolean isRedrivable() {
		return this.eventType != null && this.aggregateId != null;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

	/** 격리 원인 - 요약·상세·난 지점. 컨슈머가 예외에서 뽑아 넘긴다. */
	public record Failure(String message, String stackTrace, String step) {

		public static Failure of(Throwable failure, String step) {
			return new Failure(failure.getMessage(), stackTraceOf(failure), step);
		}

		private static String stackTraceOf(Throwable failure) {
			java.io.StringWriter writer = new java.io.StringWriter();
			failure.printStackTrace(new java.io.PrintWriter(writer));
			return writer.toString();
		}
	}
}
