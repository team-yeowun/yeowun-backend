package modi.backend.ingestionv2.enrich.interfaces;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import modi.backend.ingestionv2.enrich.domain.EnrichResult;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStatus;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

/**
 * 보강 관리자 API 출력.
 *
 * <ul>
 *   <li>도메인별 외곽 클래스에 중첩 record. 파일 1개당 1 record를 만들지 않음</li>
 *   <li>Result를 그대로 노출하지 않음. 파사드는 이 타입을 모름</li>
 *   <li>요청 record가 없는 이유는 두 API의 입력이 질의 파라미터와 경로 변수뿐이기 때문</li>
 * </ul>
 */
public final class EnrichDto {

	private EnrichDto() {
	}

	/** 스텝 하나의 현황. */
	public record StepResponse(EnrichStep step, StepStatus status, int attempts, String lastAttemptVendor,
			String lastError,
			@Schema(description = "장르 스텝에만 값이 있다. 참이면 1차 공급자가 실패해 2차로 넘어갔다는 뜻")
			Boolean fallbackUsed) {

		static StepResponse from(EnrichResult.StepView view) {
			return new StepResponse(view.step(), view.status(), view.attempts(), view.lastAttemptVendor(),
					view.lastError(), view.fallbackUsed());
		}
	}

	/** 실패한 보강 한 건. 스텝 셋이 항상 함께 실린다. */
	public record FailedResponse(String vendorKey, EnrichmentStatus status, LocalDateTime createdAt,
			List<StepResponse> steps) {

		static FailedResponse from(EnrichResult.Failed failed) {
			return new FailedResponse(failed.vendorKey(), failed.status(), failed.createdAt(),
					failed.steps().stream().map(StepResponse::from).toList());
		}
	}

	/** 스텝별 실패 건수. */
	public record StepCountResponse(EnrichStep step, long count) {

		static StepCountResponse from(EnrichResult.StepCount stepCount) {
			return new StepCountResponse(stepCount.step(), stepCount.count());
		}
	}

	/** 실패 목록 한 페이지. */
	public record FailedPageResponse(List<FailedResponse> items, int page, int size, long totalCount,
			List<StepCountResponse> stepCounts) {

		public static FailedPageResponse from(EnrichResult.FailedPage source) {
			return new FailedPageResponse(source.items().stream().map(FailedResponse::from).toList(),
					source.page(), source.size(), source.totalCount(),
					source.stepCounts().stream().map(StepCountResponse::from).toList());
		}
	}

	/** 재시도 결과. 다시 연 스텝이 비어 있으면 되돌릴 것이 없었다는 뜻이다. */
	public record ReopenResponse(String vendorKey, List<EnrichStep> reopenedSteps) {

		public static ReopenResponse from(EnrichResult.Reopened reopened) {
			return new ReopenResponse(reopened.vendorKey(), reopened.reopenedSteps());
		}
	}
}
