package modi.backend.ingestionv2.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * </ul>
 */
@ConfigurationProperties(prefix = "app.ingestion.v2")
public record IngestionProperties(
		boolean enabled,
		boolean autoDelivery,
		String consumerGroup,
		String consumerName,
		int dispatchBatchSize,
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
		int cleanupBatchSize) {

	public IngestionProperties {
		if (consumerGroup == null || consumerGroup.isBlank()) {
			consumerGroup = "ingestion-v2";
		}
		if (consumerName == null || consumerName.isBlank()) {
			consumerName = defaultConsumerName();
		}
		if (dispatchBatchSize <= 0) {
			dispatchBatchSize = 100;
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
	}

	private static String defaultConsumerName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException unknownHost) {
			return "ingestion-" + UUID.randomUUID().toString().substring(0, 8);
		}
	}
}
