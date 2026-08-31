package modi.backend.ingestionv2.common.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionDeliveryCriteria;
import modi.backend.ingestionv2.common.IngestionDeliveryFacade;
import modi.backend.ingestionv2.common.IngestionDeliveryResult;
import modi.backend.support.response.ApiResponse;

/**
 * 배달 계층 관리자 컨트롤러.
 *
 * <ul>
 *   <li>파사드만 호출 (서비스와 컴포넌트 직주입 금지)</li>
 *   <li>Criteria와 Response 변환이 이 계층의 유일한 일</li>
 *   <li>예외 처리 없음 (전역 핸들러가 ErrorCode로 매핑)</li>
 * </ul>
 */
@Hidden
@RestController
@RequestMapping("/api-admin/v1/ingestion-deliveries")
@RequiredArgsConstructor
public class AdminIngestionDeliveryV1Controller implements AdminIngestionDeliveryV1ApiSpec {

	private final IngestionDeliveryFacade ingestionDeliveryFacade;

	@Override
	@GetMapping("/dead-letters")
	public ApiResponse<IngestionDeliveryDto.DeadLetterListResponse> findDeadLetters(
			@RequestParam(name = "limit", defaultValue = "50") int limit) {
		IngestionDeliveryResult.DeadLetters result =
				ingestionDeliveryFacade.findDeadLetters(IngestionDeliveryCriteria.Listing.of(limit));
		return ApiResponse.success(IngestionDeliveryDto.DeadLetterListResponse.from(result));
	}

	@Override
	@PostMapping("/dead-letters/{deadLetterId}/redrives")
	public ApiResponse<IngestionDeliveryDto.RedriveResponse> redrive(@PathVariable long deadLetterId) {
		IngestionDeliveryResult.Redriven result =
				ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId));
		return ApiResponse.success(IngestionDeliveryDto.RedriveResponse.from(result));
	}

	@Override
	@PostMapping("/dead-letters/{deadLetterId}/ignores")
	public ApiResponse<IngestionDeliveryDto.IgnoreResponse> ignore(@PathVariable long deadLetterId) {
		IngestionDeliveryResult.Ignored result =
				ingestionDeliveryFacade.ignore(IngestionDeliveryCriteria.Ignore.of(deadLetterId));
		return ApiResponse.success(IngestionDeliveryDto.IgnoreResponse.from(result));
	}

	@Override
	@GetMapping("/outbox-failures")
	public ApiResponse<IngestionDeliveryDto.OutboxFailureListResponse> findOutboxFailures(
			@RequestParam(name = "limit", defaultValue = "50") int limit) {
		IngestionDeliveryResult.OutboxFailures result =
				ingestionDeliveryFacade.findOutboxFailures(IngestionDeliveryCriteria.Listing.of(limit));
		return ApiResponse.success(IngestionDeliveryDto.OutboxFailureListResponse.from(result));
	}

	@Override
	@PostMapping("/outbox-failures/{outboxId}/retries")
	public ApiResponse<IngestionDeliveryDto.OutboxRetryResponse> retryOutbox(@PathVariable long outboxId) {
		IngestionDeliveryResult.OutboxRetried result =
				ingestionDeliveryFacade.retryOutbox(IngestionDeliveryCriteria.OutboxRetry.of(outboxId));
		return ApiResponse.success(IngestionDeliveryDto.OutboxRetryResponse.from(result));
	}

	@Override
	@GetMapping("/streams")
	public ApiResponse<IngestionDeliveryDto.StreamStatusListResponse> findStreams() {
		IngestionDeliveryResult.Streams result = ingestionDeliveryFacade.findStreams();
		return ApiResponse.success(IngestionDeliveryDto.StreamStatusListResponse.from(result));
	}
}
