package modi.backend.ingestionv2.enrich.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 보강 유스케이스 출력.
 *
 * <ul>
 *   <li>애그리거트를 그대로 내보내지 않고 화면이 묻는 값만 담음</li>
 *   <li>스텝 셋을 항상 함께 담아 어느 스텝이 막혔는지를 한 항목 안에서 답함</li>
 * </ul>
 */
public final class EnrichResult {

	private EnrichResult() {
	}

	/** 스텝 하나의 진행 현황. fallbackUsed는 장르에만 값이 있다. */
	public record StepView(EnrichStep step, StepStatus status, int attempts, String lastAttemptVendor,
			String lastError, Boolean fallbackUsed) {

		static StepView of(Enrichment enrichment, EnrichStep step) {
			return new StepView(step, enrichment.statusOf(step), enrichment.attemptsOf(step),
					enrichment.lastAttemptVendorOf(step), enrichment.lastErrorOf(step),
					step == EnrichStep.GENRE ? enrichment.isGenreFallbackUsed() : null);
		}
	}

	/** 실패한 보강 한 건. */
	public record Failed(String vendorKey, EnrichmentStatus status, LocalDateTime createdAt, List<StepView> steps) {

		public static Failed from(Enrichment enrichment) {
			return new Failed(enrichment.getVendorKey(), enrichment.getStatus(), enrichment.getCreatedAt(),
					List.of(StepView.of(enrichment, EnrichStep.DETAIL),
							StepView.of(enrichment, EnrichStep.GENRE),
							StepView.of(enrichment, EnrichStep.HOURS)));
		}
	}

	/** 스텝별 실패 건수. 어느 벤더가 문제인지를 한 줄로 답한다. */
	public record StepCount(EnrichStep step, long count) {
	}

	/** 실패 목록 한 페이지. 오프셋 페이지네이션이라 전체 건수를 함께 돌려준다. */
	public record FailedPage(List<Failed> items, int page, int size, long totalCount, List<StepCount> stepCounts) {
	}

	/** 수동 재시도 결과. 이번 호출이 실제로 다시 연 스텝만 담긴다. */
	public record Reopened(String vendorKey, List<EnrichStep> reopenedSteps) {
	}
}
