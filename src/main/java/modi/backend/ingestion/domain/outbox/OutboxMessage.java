package modi.backend.ingestion.domain.outbox;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 전시 초기화 트랜잭션 아웃박스 메시지 — {@code exhibition_outbox} 매핑. <b>이 설계의 심장</b>이다(ADR-10).
 *
 * <p><b>아웃박스의 본질은 원자성이다</b>: 메시지는 그것을 필요하게 만든 상태 변경과 <b>같은 트랜잭션</b>에서
 * 기록된다 — 전시(또는 draft)는 저장됐는데 후속 작업 기록이 유실되는 창이 없다. 스프링 이벤트는 커밋 직후
 * 드레인을 앞당기는 글루일 뿐이고(비durable — 크래시 유실·재시도 없음), durability·재시도는 이 테이블과
 * 릴레이 폴러가 진다("이벤트=글루, 테이블=엔진").
 *
 * <p><b>at-least-once는 코드가 아니라 테이블이다</b>: "조금 늦어도 최소 1회 무조건"은 진행 상태가 DB에 남아야만
 * 보장된다(재시작 생존). 벤더 테이블은 원본만, 정준 테이블은 결과만, <b>진행 상태는 이 테이블만 안다</b>.
 *
 * <p><b>멱등 enqueue</b>: UK{@code (message_type, target_key)}라 같은 대상에 중복 메시지가 생기지 않는다(예: 한 번의
 * 카탈로그 sync에서 같은 장소에 전시가 여럿 들어와도 PLACE_HOURS_STALE은 1건). <b>선별</b>: 인덱스
 * {@code (status, next_attempt_at)}로 폴링 쿼리({@code status IN (PENDING, RETRYABLE) AND next_attempt_at <= now})가
 * 풀스캔 없이 도래한 메시지만 집는다.
 *
 * <p><b>낙관락({@link Version})</b>: 릴레이(스케줄)와 이벤트 드레인이 같은 메시지를 동시에 집으면, 종료 전이 저장에서
 * 한쪽만 이기고 다른 쪽은 {@code OptimisticLockException}으로 밀린다 = 다른 워커가 선점 = 정상 skip.
 *
 * <p>재생성될 수 있는 파이프라인 테이블이라 {@code BaseEntity}(soft delete·감사)를 상속하지 않고
 * {@code created_at/updated_at}만 자체 관리한다(다른 벤더·정준 테이블과 같은 규율).
 */
@Entity
@Table(name = "exhibition_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

	/** last_error가 무한정 커지지 않게 저장 전 자르는 상한(원인 식별엔 충분하다). */
	private static final int MAX_ERROR_LENGTH = 1000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "message_type", nullable = false, length = 30)
	private IngestionEventType messageType;

	/** 작업 대상 식별자 — 종류에 따라 {@code external_id}(상세·장르) 또는 {@code place_key}(영업시간)다. */
	@Column(name = "target_key", nullable = false, length = 500)
	private String targetKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OutboxMessageStatus status;

	/** 지금까지의 시도 횟수 — 백오프 간격 계산과 최대 시도 초과 판정의 재료. */
	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	/** 다음 재시도 도래 시각 — 선별 쿼리의 경계값. PENDING이면 즉시(=enqueue 시각). */
	@Column(name = "next_attempt_at")
	private LocalDateTime nextAttemptAt;

	/** 마지막 실패 원인(항목 단위 가시화). 성공 시 null로 지운다. */
	@Column(name = "last_error", columnDefinition = "text")
	private String lastError;

	/** 낙관락 버전 — 동시 클레임 제어(다른 워커 선점 시 저장 충돌 → skip). */
	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	/** 종료(성공·영구실패) 시각. 미종료면 null. */
	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private OutboxMessage(IngestionEventType messageType, String targetKey, LocalDateTime now) {
		this.messageType = messageType;
		this.targetKey = targetKey;
		this.status = OutboxMessageStatus.PENDING;
		this.attemptCount = 0;
		this.nextAttemptAt = now;
	}

	/**
	 * 작업을 큐에 넣는다(멱등 enqueue의 생산자 — 중복 방지는 UK가 맡는다). 처음부터 즉시 선별 대상(PENDING·now).
	 */
	public static OutboxMessage enqueue(IngestionEventType messageType, String targetKey, LocalDateTime now) {
		if (messageType == null) {
			throw new IllegalArgumentException("messageType은 필수다");
		}
		if (targetKey == null || targetKey.isBlank()) {
			throw new IllegalArgumentException("targetKey는 필수다");
		}
		return new OutboxMessage(messageType, targetKey, now);
	}

	/** 작업이 성공했다. 종료 상태로 전이하고 원인 흔적을 지운다. */
	public void succeed(LocalDateTime now) {
		this.status = OutboxMessageStatus.SUCCEEDED;
		this.lastError = null;
		this.completedAt = now;
		this.nextAttemptAt = null;
	}

	/**
	 * 실패를 기록하고 다음 상태를 정한다(상태 전이의 단일 지점 — 규칙은 Entity 안에서만).
	 * <ul>
	 *   <li>{@link OutboxFailureType#PERMANENT}: 즉시 {@link OutboxMessageStatus#FAILED_PERMANENT}.</li>
	 *   <li>{@link OutboxFailureType#RETRYABLE}: 시도 횟수를 늘리고, 최대 시도를 넘겼으면 PERMANENT로 승격(무한 재시도 방지),
	 *       아니면 {@link OutboxMessageStatus#FAILED_RETRYABLE} + 지수 백오프로 {@code next_attempt_at}를 민다.</li>
	 * </ul>
	 */
	public void recordFailure(OutboxFailureType failureType, String error, RetryPolicy policy, LocalDateTime now) {
		this.attemptCount++;
		this.lastError = truncate(error);
		if (failureType == OutboxFailureType.PERMANENT) {
			this.status = OutboxMessageStatus.FAILED_PERMANENT;
			this.completedAt = now;
			this.nextAttemptAt = null;
			return;
		}
		if (policy.isExhausted(this.attemptCount)) {
			// 재시도 가능한 실패라도 시도를 소진하면 사람에게 보이게 영구 실패로 승격한다.
			this.status = OutboxMessageStatus.FAILED_PERMANENT;
			this.completedAt = now;
			this.nextAttemptAt = null;
			return;
		}
		this.status = OutboxMessageStatus.FAILED_RETRYABLE;
		this.completedAt = null;
		this.nextAttemptAt = policy.nextAttemptAt(this.attemptCount, now);
	}

	/**
	 * 종료된 작업을 되살려 다시 큐에 넣는다(이벤트 구동 재검증·수동 재시도). 시도 횟수를 리셋해 새 시도로 취급한다.
	 * 이미 미종료(PENDING/RETRYABLE)면 아무것도 하지 않는다 — 이미 선별 대상이라 재활성화가 불필요하다.
	 */
	public void reactivate(LocalDateTime now) {
		if (!this.status.isTerminal()) {
			return;
		}
		this.status = OutboxMessageStatus.PENDING;
		this.attemptCount = 0;
		this.nextAttemptAt = now;
		this.lastError = null;
		this.completedAt = null;
	}

	/** 선별 대상인가 — 미종료이고 재시도 시각이 도래했는가. */
	public boolean isDue(LocalDateTime now) {
		return this.status.isPending() && this.nextAttemptAt != null && !this.nextAttemptAt.isAfter(now);
	}

	/** 종료 상태(성공·영구실패)인가. */
	public boolean isTerminal() {
		return this.status.isTerminal();
	}

	private static String truncate(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
	}

	@PrePersist
	private void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	private void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
