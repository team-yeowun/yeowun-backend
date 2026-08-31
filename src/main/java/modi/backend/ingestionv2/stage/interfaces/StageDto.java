package modi.backend.ingestionv2.stage.interfaces;

import java.time.LocalDateTime;
import java.util.List;

import modi.backend.ingestionv2.stage.domain.StageResult;

/**
 * 관리자 스테이징 API 의 요청과 응답.
 *
 * <ul>
 *   <li>도메인별 외곽 클래스에 중첩 record 로 묶어 파일 수를 줄임</li>
 *   <li>변환은 이 파일의 정적 팩토리에만 두어 컨트롤러에 로직이 남지 않게 함</li>
 * </ul>
 */
public final class StageDto {

	private StageDto() {
	}

	/** 실패 목록 항목. */
	public record FailedResponse(String vendorKey, int attempts, String lastError, LocalDateTime updatedAt) {

		public static FailedResponse from(StageResult.Failed failed) {
			return new FailedResponse(failed.vendorKey(), failed.attempts(), failed.lastError(), failed.updatedAt());
		}
	}

	/** 실패 목록 한 페이지. 오프셋 페이지네이션이라 전체 건수를 함께 돌려준다. */
	public record FailedPageResponse(List<FailedResponse> items, int page, int size, long totalCount) {

		public static FailedPageResponse from(StageResult.FailedPage page) {
			return new FailedPageResponse(page.items().stream().map(FailedResponse::from).toList(),
					page.page(), page.size(), page.totalCount());
		}
	}

	/** 재시도 결과. */
	public record ReopenedResponse(String vendorKey) {

		public static ReopenedResponse from(StageResult.Reopened reopened) {
			return new ReopenedResponse(reopened.vendorKey());
		}
	}
}
