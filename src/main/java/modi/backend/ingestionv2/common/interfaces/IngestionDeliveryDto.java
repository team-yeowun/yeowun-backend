package modi.backend.ingestionv2.common.interfaces;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import modi.backend.ingestionv2.common.IngestionDeliveryResult;

/**
 * 배달 계층 관리자 API 입출력.
 *
 * <ul>
 *   <li>도메인별 외곽 클래스에 중첩 record (파일 1개당 1 record 금지 규칙)</li>
 *   <li>Result를 그대로 노출하지 않음 (내부 어휘를 바꿔도 외부 계약이 흔들리지 않게)</li>
 *   <li>요청 본문 없음 - 네 진입점의 입력이 상한과 식별자뿐이다</li>
 * </ul>
 */
public final class IngestionDeliveryDto {

	private IngestionDeliveryDto() {
	}

	public record DeadLetterItemResponse(
			long id,
			@Schema(description = "해석 불가 레코드는 비어 있다") String aggregateType,
			@Schema(description = "해석 불가 레코드는 비어 있다") String aggregateId,
			@Schema(description = "해석 불가 레코드는 비어 있다") String eventType,
			@Schema(description = "걷어낸 스트림 레코드의 payload 원문. 필드가 없던 레코드는 비어 있다") String payload,
			String streamKey,
			String recordId,
			@Schema(description = "실패 원인 요약") String errorMessage,
			@Schema(description = "애플리케이션이 남긴 상세 스택") String stackTrace,
			@Schema(description = "오류가 난 지점. 핸들러 이름 또는 DECODE") String failedStep,
			@Schema(description = "격리 전까지 처리를 시도한 횟수. 해석 불가는 0") int retryCount,
			LocalDateTime failedAt,
			@Schema(description = "PENDING / REPLAYED / IGNORED") String status) {

		public static DeadLetterItemResponse from(IngestionDeliveryResult.DeadLetterItem item) {
			return new DeadLetterItemResponse(item.id(), item.aggregateType(), item.aggregateId(), item.eventType(),
					item.payload(), item.streamKey(), item.recordId(), item.errorMessage(), item.stackTrace(),
					item.failedStep(), item.retryCount(), item.failedAt(), item.status());
		}
	}

	public record DeadLetterListResponse(int count, List<DeadLetterItemResponse> items) {

		public static DeadLetterListResponse from(IngestionDeliveryResult.DeadLetters result) {
			return new DeadLetterListResponse(result.count(),
					result.items().stream().map(DeadLetterItemResponse::from).toList());
		}
	}

	public record OutboxFailureItemResponse(
			long id,
			String aggregateType,
			String aggregateId,
			String eventType,
			@Schema(description = "컨슈머가 읽는 이벤트 데이터 JSON") String payload,
			@Schema(description = "발행 시도 횟수. 소비 재시도 횟수가 아니다") int retryCount,
			LocalDateTime createdAt) {

		public static OutboxFailureItemResponse from(IngestionDeliveryResult.OutboxFailureItem item) {
			return new OutboxFailureItemResponse(item.id(), item.aggregateType(), item.aggregateId(),
					item.eventType(), item.payload(), item.retryCount(), item.createdAt());
		}
	}

	public record OutboxFailureListResponse(int count, List<OutboxFailureItemResponse> items) {

		public static OutboxFailureListResponse from(IngestionDeliveryResult.OutboxFailures result) {
			return new OutboxFailureListResponse(result.count(),
					result.items().stream().map(OutboxFailureItemResponse::from).toList());
		}
	}

	public record OutboxRetryResponse(long outboxId, String eventType, String aggregateId) {

		public static OutboxRetryResponse from(IngestionDeliveryResult.OutboxRetried result) {
			return new OutboxRetryResponse(result.outboxId(), result.eventType(), result.aggregateId());
		}
	}

	public record StreamStatusResponse(
			String streamKey,
			@Schema(description = "거짓이면 컨슈머 그룹 부트스트랩이 실행되지 않았다는 뜻") boolean groupExists,
			long length,
			long consumerCount,
			long pendingCount,
			@Schema(description = "트리밍 이후 계산이 불가능해지면 비어 있다") Long lag) {

		public static StreamStatusResponse from(IngestionDeliveryResult.StreamItem item) {
			return new StreamStatusResponse(item.streamKey(), item.groupExists(), item.length(),
					item.consumerCount(), item.pendingCount(), item.lag());
		}
	}

	public record StreamStatusListResponse(List<StreamStatusResponse> items) {

		public static StreamStatusListResponse from(IngestionDeliveryResult.Streams result) {
			return new StreamStatusListResponse(
					result.items().stream().map(StreamStatusResponse::from).toList());
		}
	}

	public record IgnoreResponse(long deadLetterId, String status) {

		public static IgnoreResponse from(IngestionDeliveryResult.Ignored result) {
			return new IgnoreResponse(result.deadLetterId(), result.status());
		}
	}

	public record RedriveResponse(long deadLetterId, String eventType, String aggregateId) {

		public static RedriveResponse from(IngestionDeliveryResult.Redriven result) {
			return new RedriveResponse(result.deadLetterId(), result.eventType(), result.aggregateId());
		}
	}
}
