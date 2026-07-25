package modi.backend.ingestion.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.admin.IngestionAdminFacade;
import modi.backend.ingestion.application.admin.IngestionAdminResult;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.progress.ProgressStatus;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;
import modi.backend.support.response.ApiResponse;

/**
 * 수집 파이프라인 관리자 API(설계 §7·D5) — {@code /api-admin/**} 게이트는 {@code AdminAuthInterceptor}가
 * 담당하므로 여기엔 인증 코드가 없다. 관리자 전용이라 Swagger에 노출하지 않는다({@code @Hidden} —
 * 기존 Admin 컨트롤러들과 같은 규율). 코어→ingestion 참조 금지 규칙에 따라 이 컨트롤러는 ingestion
 * 슬라이스 안(interfaces)에 있다.
 *
 * <p>대시보드 2층 구조: 요약(summary)·런 목록(runs) → 아이템 상세(progress·outbox). 수동 재시도는
 * 영구 실패의 유일한 회생 경로다(자동 치유 없음 — D5).
 */
@Hidden
@RestController
@RequestMapping("/api-admin/v1/ingestion")
@RequiredArgsConstructor
public class AdminIngestionV1Controller {

	private final IngestionAdminFacade ingestionAdminFacade;

	@GetMapping("/summary")
	public ApiResponse<IngestionAdminResult.Summary> summary() {
		return ApiResponse.success(ingestionAdminFacade.summary());
	}

	@GetMapping("/runs")
	public ApiResponse<IngestionAdminResult.Page<IngestionAdminResult.Run>> runs(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(ingestionAdminFacade.runs(page, size));
	}

	@GetMapping("/progress")
	public ApiResponse<IngestionAdminResult.Page<IngestionAdminResult.Progress>> progress(
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(ingestionAdminFacade.progress(parseProgressStatus(status), page, size));
	}

	@GetMapping("/outbox")
	public ApiResponse<IngestionAdminResult.Page<IngestionAdminResult.Outbox>> outbox(
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(ingestionAdminFacade.outbox(parseOutboxStatus(status), page, size));
	}

	/** 진행 단위 수동 재시도 — FAILED 재개 + 미해소 스텝 이벤트 부활(릴레이가 즉시 소비). */
	@PostMapping("/progress/{externalId}/retry")
	public ApiResponse<IngestionAdminResult.Retried> retryProgress(@PathVariable String externalId) {
		return ApiResponse.success(ingestionAdminFacade.retryProgress(externalId));
	}

	/** SUCCEEDED 정리 수동 트리거 — 주간 스케줄(§9)과 같은 경로(보존 기간·소량 배치 동일). */
	@PostMapping("/outbox/purge")
	public ApiResponse<IngestionAdminResult.Purged> purgeOutbox() {
		return ApiResponse.success(ingestionAdminFacade.purgeSucceeded(java.time.LocalDateTime.now()));
	}

	/** 아웃박스 메시지 단위 수동 재시도 — 전시장 축 등 진행과 무관한 축의 영구 실패 회생. */
	@PostMapping("/outbox/{messageId}/retry")
	public ApiResponse<IngestionAdminResult.Retried> retryOutbox(@PathVariable Long messageId) {
		return ApiResponse.success(ingestionAdminFacade.retryOutbox(messageId));
	}

	private static ProgressStatus parseProgressStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return ProgressStatus.valueOf(status.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new CoreException(ErrorType.INVALID_INPUT, "알 수 없는 진행 상태: " + status);
		}
	}

	private static OutboxMessageStatus parseOutboxStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return OutboxMessageStatus.valueOf(status.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new CoreException(ErrorType.INVALID_INPUT, "알 수 없는 아웃박스 상태: " + status);
		}
	}
}
