package modi.backend.ingestionv2.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

import modi.backend.ingestionv2.common.outbox.OutboxClaimStrategy;
import modi.backend.ingestionv2.common.outbox.OutboxReadSource;
import modi.backend.ingestionv2.common.queue.ConsumeHandler;
import modi.backend.ingestionv2.common.queue.ReclaimBackoff;
import modi.backend.ingestionv2.common.queue.ReclaimJitter;

/**
 * 수집 슬라이스의 운영 파라미터.
 *
 * <ul>
 *   <li>컨슈머 수는 설정, 대기열 수는 코드 - 전자는 운영 판단이고 후자는 설계 판단</li>
 *   <li>컨슈머 이름 기본값은 호스트명 - 인스턴스마다 달라야 미처리 목록이 구분됨</li>
 *   <li>enabled는 슬라이스 스위치, autoDelivery는 비동기 배달 스위치 - 둘을 갈라 테스트가 리스너·스케줄러만 끌 수 있게 함</li>
 *   <li>회수 백오프·지터는 정책이라 설정 - 코드에 박으면 운영 중에 되돌릴 수 없고, 되돌릴 수 없는 정책은 배포 사고가 된다</li>
 *   <li>선점 전략·조회 대상도 설정 - 락 방식은 같은 무대에서 바꿔 가며 재야 고를 수 있는 종류의 판단이다.
 *       운영 값은 REDIS_MARKER + MASTER 고정이고 나머지 값은 부하 실험 비교용</li>
 *   <li>배치 상한 0 = 상한 없음 - 음수만 기본값으로 되돌린다. "상한을 두지 않는다"가 실제로 표현 가능해야
 *       상한이 왜 필요한지를 잴 수 있다</li>
 *   <li>소비 스위치를 발송과 갈라 둠 - 발송 부하만 재는 동안 컨슈머가 외부 API 를 호출하는 것을 막는 유일한 장치</li>
 *   <li>소비 핸들러 선택도 설정 - 운영 값은 REAL 하나이고 STUB 은 부하 실험 비교용이다.
 *       소비를 켠 채로 벤더 호출만 없애야 배분과 처리량을 재는 무대가 선다</li>
 *   <li>발송은 리더 1대 - 소비 병렬성은 컨슈머 그룹이 맡으므로 발송을 여러 대가 할 이유가 없다. 발송 락 TTL 은
 *       공용 잡 락보다 짧게 따로 둔다 - 정상 종료는 해제하므로 TTL 은 강제 종료 때의 정지 시간일 뿐이다.
 *       리더 락·깨우기 스위치는 부하 실험 before 를 재현하려고 끌 수 있게 열어 둔다</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.ingestion.v2")
public record IngestionProperties(
		boolean enabled,
		boolean autoDelivery,
		String consumerGroup,
		String consumerName,
		int dispatchBatchSize,
		OutboxClaimStrategy claimStrategy,
		OutboxReadSource outboxRead,
		boolean dispatchDrain,
		int dispatchDrainMaxBatches,
		boolean dispatchLeaderLock,
		long dispatchLockTtlMs,
		boolean dispatchWakeEnabled,
		long markerTtlMs,
		long jobLockTtlMs,
		boolean consumeEnabled,
		ConsumeHandler consumeHandler,
		long stubLatencyMs,
		boolean outboxPendingGaugeEnabled,
		int externalStreamConsumers,
		int dbStreamConsumers,
		long pollTimeoutMs,
		int readBatchSize,
		long reclaimIdleSeconds,
		long reclaimMaxIdleSeconds,
		ReclaimBackoff reclaimBackoff,
		ReclaimJitter reclaimJitter,
		int reclaimBatchSize,
		long streamMaxLength,
		int maxAttempts,
		int retentionDays,
		int cleanupBatchSize,
		long inboxLeaseMs,
		int inboxRetentionDays,
		int inboxCleanupBatchSize,
		int inboxCleanupMaxBatches) {

	public IngestionProperties {
		if (consumerGroup == null || consumerGroup.isBlank()) {
			consumerGroup = "ingestion-v2";
		}
		if (consumerName == null || consumerName.isBlank()) {
			consumerName = defaultConsumerName();
		}
		if (dispatchBatchSize < 0) {
			dispatchBatchSize = 100;
		}
		if (claimStrategy == null) {
			claimStrategy = OutboxClaimStrategy.REDIS_MARKER;
		}
		if (outboxRead == null) {
			outboxRead = OutboxReadSource.MASTER;
		}
		if (dispatchDrainMaxBatches <= 0) {
			dispatchDrainMaxBatches = 100_000;
		}
		if (dispatchLockTtlMs <= 0) {
			dispatchLockTtlMs = 30_000;
		}
		if (markerTtlMs <= 0) {
			markerTtlMs = 300_000;
		}
		if (jobLockTtlMs <= 0) {
			jobLockTtlMs = 300_000;
		}
		if (consumeHandler == null) {
			consumeHandler = ConsumeHandler.REAL;
		}
		if (stubLatencyMs < 0) {
			stubLatencyMs = 0;
		}
		if (externalStreamConsumers <= 0) {
			externalStreamConsumers = 2;
		}
		if (dbStreamConsumers <= 0) {
			dbStreamConsumers = 1;
		}
		if (pollTimeoutMs <= 0) {
			pollTimeoutMs = 2000;
		}
		if (readBatchSize <= 0) {
			readBatchSize = 10;
		}
		if (reclaimIdleSeconds <= 0) {
			reclaimIdleSeconds = 60;
		}
		if (reclaimMaxIdleSeconds < reclaimIdleSeconds) {
			reclaimMaxIdleSeconds = reclaimIdleSeconds * 10;
		}
		if (reclaimBackoff == null) {
			reclaimBackoff = ReclaimBackoff.EXPONENTIAL;
		}
		if (reclaimJitter == null) {
			reclaimJitter = ReclaimJitter.FULL;
		}
		if (reclaimBatchSize <= 0) {
			reclaimBatchSize = 100;
		}
		if (streamMaxLength <= 0) {
			streamMaxLength = 100_000;
		}
		if (maxAttempts <= 0) {
			maxAttempts = 3;
		}
		if (retentionDays <= 0) {
			retentionDays = 7;
		}
		if (cleanupBatchSize <= 0) {
			cleanupBatchSize = 500;
		}
		if (inboxLeaseMs <= 0) {
			inboxLeaseMs = 600_000;
		}
		if (inboxRetentionDays <= 0) {
			inboxRetentionDays = 30;
		}
		if (inboxCleanupBatchSize <= 0) {
			inboxCleanupBatchSize = 500;
		}
		if (inboxCleanupMaxBatches <= 0) {
			inboxCleanupMaxBatches = 20;
		}
	}

	private static String defaultConsumerName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException unknownHost) {
			return "ingestion-" + UUID.randomUUID().toString().substring(0, 8);
		}
	}
}
