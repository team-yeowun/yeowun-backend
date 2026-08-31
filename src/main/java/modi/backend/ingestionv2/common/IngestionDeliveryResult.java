package modi.backend.ingestionv2.common;

import java.time.LocalDateTime;
import java.util.List;

import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.queue.StreamStatus;

/**
 * 배달 계층 관리자 유스케이스의 출력.
 *
 * <ul>
 *   <li>엔티티를 그대로 내보내지 않음 - 인터페이스 계층이 Result까지만 본다</li>
 *   <li>격리 항목의 retry_count 는 격리 전까지의 시도 횟수 - 도메인 애그리거트의 현재 값과는 다를 수 있다</li>
 *   <li>스트림 lag은 비어 있을 수 있음 - 트리밍 이후 Redis가 계산하지 못하는 구간이 있다</li>
 * </ul>
 */
public final class IngestionDeliveryResult {

	private IngestionDeliveryResult() {
	}

	public record DeadLetters(int count, List<DeadLetterItem> items) {

		public static DeadLetters from(List<DeadLetter> found) {
			List<DeadLetterItem> items = found.stream().map(DeadLetterItem::from).toList();
			return new DeadLetters(items.size(), items);
		}
	}

	public record DeadLetterItem(long id, String aggregateType, String aggregateId, String eventType, String payload,
			String streamKey, String recordId, String errorMessage, String stackTrace, String failedStep,
			int retryCount, LocalDateTime failedAt, String status) {

		public static DeadLetterItem from(DeadLetter deadLetter) {
			return new DeadLetterItem(
					deadLetter.getId(),
					deadLetter.getAggregateType() == null ? null : deadLetter.getAggregateType().name(),
					deadLetter.getAggregateId(),
					deadLetter.getEventType() == null ? null : deadLetter.getEventType().name(),
					deadLetter.getPayload(),
					deadLetter.getStreamKey(),
					deadLetter.getRecordId(),
					deadLetter.getErrorMessage(),
					deadLetter.getStackTrace(),
					deadLetter.getFailedStep(),
					deadLetter.getRetryCount(),
					deadLetter.getFailedAt(),
					deadLetter.getStatus().name());
		}
	}

	public record OutboxFailures(int count, List<OutboxFailureItem> items) {

		public static OutboxFailures from(List<Outbox> found) {
			List<OutboxFailureItem> items = found.stream().map(OutboxFailureItem::from).toList();
			return new OutboxFailures(items.size(), items);
		}
	}

	public record OutboxFailureItem(long id, String aggregateType, String aggregateId, String eventType,
			String payload, int retryCount, LocalDateTime createdAt) {

		public static OutboxFailureItem from(Outbox outbox) {
			return new OutboxFailureItem(
					outbox.getId(),
					outbox.getAggregateType().name(),
					outbox.getAggregateId(),
					outbox.getEventType().name(),
					outbox.getPayload(),
					outbox.getRetryCount(),
					outbox.getCreatedAt());
		}
	}

	public record OutboxRetried(long outboxId, String eventType, String aggregateId) {
	}

	public record Streams(List<StreamItem> items) {

		public static Streams from(List<StreamStatus> statuses) {
			return new Streams(statuses.stream().map(StreamItem::from).toList());
		}
	}

	public record StreamItem(String streamKey, boolean groupExists, long length, long consumerCount,
			long pendingCount, Long lag) {

		public static StreamItem from(StreamStatus status) {
			return new StreamItem(status.streamKey(), status.groupExists(), status.length(), status.consumerCount(),
					status.pendingCount(), status.lag());
		}
	}

	public record Redriven(long deadLetterId, String eventType, String aggregateId) {
	}

	public record Ignored(long deadLetterId, String status) {
	}
}
