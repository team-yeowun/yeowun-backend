package modi.backend.ingestionv2.common;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.deadletter.DeadLetterService;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxService;
import modi.backend.ingestionv2.common.queue.StreamStatus;
import modi.backend.ingestionv2.common.queue.StreamStatusReader;

/**
 * 배달 계층 관리자 유스케이스의 진입점.
 *
 * <ul>
 *   <li>관리자 유스케이스에서 두 창구(격리와 아웃박스)를 조율하는 유일한 자리 - 서비스끼리 서로 부르지 않음</li>
 *   <li>상태 판단을 하지 않음 - 재주입 가능 여부는 격리 창구가 판정</li>
 *   <li>재주입은 REPLAYED 상태 전이와 새 Outbox 적재를 한 트랜잭션으로 커밋 - 둘 중 하나만 남는 복구 유실을 차단</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class IngestionDeliveryFacade {

	private final DeadLetterService deadLetterService;
	private final OutboxService outboxService;
	private final StreamStatusReader streamStatusReader;

	/** 격리 목록 - 관리자가 아직 처리하지 않은 항목만. */
	public IngestionDeliveryResult.DeadLetters findDeadLetters(IngestionDeliveryCriteria.Listing criteria) {
		List<DeadLetter> found = deadLetterService.findPending(criteria.limit());
		return IngestionDeliveryResult.DeadLetters.from(found);
	}

	/** 발행 실패 목록 - 재시도 상한을 넘겨 FAILED로 걷어낸 행. */
	public IngestionDeliveryResult.OutboxFailures findOutboxFailures(IngestionDeliveryCriteria.Listing criteria) {
		List<Outbox> found = outboxService.findFailed(criteria.limit());
		return IngestionDeliveryResult.OutboxFailures.from(found);
	}

	/** 발행 실패 재시도 - FAILED 행을 PENDING으로 되돌려 다음 발송 틱이 집게 한다. */
	public IngestionDeliveryResult.OutboxRetried retryOutbox(IngestionDeliveryCriteria.OutboxRetry criteria) {
		Outbox retried = outboxService.retry(criteria.outboxId());
		return new IngestionDeliveryResult.OutboxRetried(retried.getId(), retried.getEventType().name(),
				retried.getAggregateId());
	}

	/** 스트림 상태 - 네 스트림 전부. */
	public IngestionDeliveryResult.Streams findStreams() {
		List<StreamStatus> statuses = streamStatusReader.readAll();
		return IngestionDeliveryResult.Streams.from(statuses);
	}

	/** 무시 - 처리하지 않기로 한 항목을 목록에서 뺀다. 기록은 남는다. */
	public IngestionDeliveryResult.Ignored ignore(IngestionDeliveryCriteria.Ignore criteria) {
		DeadLetter ignored = deadLetterService.markIgnored(criteria.deadLetterId());
		return new IngestionDeliveryResult.Ignored(ignored.getId(), ignored.getStatus().name());
	}

	/** 재주입 - REPLAYED 표시와 새 Outbox를 함께 커밋하고, @Version 충돌은 표시 flush에서 판정한다. */
	@Transactional
	public IngestionDeliveryResult.Redriven redrive(IngestionDeliveryCriteria.Redrive criteria) {
		DeadLetter target = deadLetterService.findRedrivable(criteria.deadLetterId());
		deadLetterService.markReplayed(criteria.deadLetterId());
		outboxService.append(target.getEventType(), target.getAggregateId());
		return new IngestionDeliveryResult.Redriven(target.getId(), target.getEventType().name(),
				target.getAggregateId());
	}
}
