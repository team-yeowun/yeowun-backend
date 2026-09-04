package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxAppender;

/**
 * 수집의 트랜잭션 경계 소유자.
 *
 * <ul>
 *   <li>메서드 하나 = 트랜잭션 하나 (경계를 어노테이션 위치로 읽을 수 있게)</li>
 *   <li>외부 호출 없음 (문화포털 호출은 파사드가 이 클래스 밖에서 수행)</li>
 *   <li>상태 변경은 전부 엔티티 팩토리 안에서 (서비스는 조회·저장·적재만)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CollectService {

	private final CollectRepository collectRepository;
	private final OutboxAppender outboxAppender;
	private final IngestionProperties properties;

	/**
	 * 핵심 트랜잭션 1 회차 선점.
	 *
	 * <ul>
	 *   <li>목록 조회 이전에 단독으로 커밋 (다른 인스턴스에게 즉시 보이게)</li>
	 *   <li>COMPLETED/유효한 RUNNING은 선점하지 않고, FAILED/만료 RUNNING은 새 token으로 재선점</li>
	 * </ul>
	 */
	@Transactional
	public Optional<CollectBatchClaim> claimBatch(LocalDate batchDate) {
		LocalDateTime claimedAt = IngestionClock.now();
		LocalDateTime leaseUntil = claimedAt.plus(Duration.ofMillis(properties.collectLeaseMs()));
		return collectRepository.claimBatch(batchDate, claimedAt, leaseUntil, UUID.randomUUID().toString());
	}

	@Transactional
	public boolean completeBatch(CollectBatchClaim claim) {
		return collectRepository.completeBatch(claim, IngestionClock.now());
	}

	@Transactional
	public boolean failBatch(CollectBatchClaim claim, String lastError) {
		return collectRepository.failBatch(claim, summarize(lastError));
	}

	/**
	 * 핵심 트랜잭션 2 전시 1건 확정.
	 *
	 * <ul>
	 *   <li>애그리거트·원장·이벤트 세 가지가 한 트랜잭션 (셋이 어긋난 구간을 만들지 않음)</li>
	 *   <li>이미 확정된 전시는 아무 일도 하지 않고 false (재실행 멱등)</li>
	 * </ul>
	 */
	@Transactional
	public boolean record(LocalDate batchDate, CatalogItem item) {
		if (collectRepository.existsByVendorKey(item.vendorKey())) {
			return false;
		}
		collectRepository.save(CollectedExhibition.create(item, batchDate));
		collectRepository.saveSnapshot(CultureListSnapshot.observe(item));
		outboxAppender.append(IngestionEventType.COLLECTED, item.vendorKey());
		return true;
	}

	private String summarize(String lastError) {
		if (lastError == null || lastError.isBlank()) {
			return "unknown failure";
		}
		return lastError.length() <= 1000 ? lastError : lastError.substring(0, 1000);
	}
}
