package modi.backend.interfaces.search.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import modi.backend.application.search.SearchHistoryResult;

/** 검색 기록 API 요청/응답 DTO 모음. */
public final class SearchHistoryDto {

	private SearchHistoryDto() {
	}

	/** 검색어 기록 요청 — 검색이 확정된 시점에 클라이언트가 보낸다. */
	public record RecordRequest(
			@Schema(description = "검색어(앞뒤 공백·연속 공백은 서버가 정리)", example = "김환기")
			@NotBlank(message = "검색어는 필수입니다.")
			@Size(min = 2, max = 100, message = "검색어는 2~100글자여야 합니다.")
			String keyword) {
	}

	/** 최근 검색어 목록 응답. 최대 10개 고정이라 커서가 없다. */
	public record RecentListResponse(
			@Schema(description = "최근 검색어(최신순, 최대 10)") List<ItemResponse> content) {

		public static RecentListResponse from(SearchHistoryResult.RecentList result) {
			return new RecentListResponse(result.content().stream().map(ItemResponse::from).toList());
		}
	}

	/** 최근 검색어 한 건. */
	public record ItemResponse(
			@Schema(description = "검색 기록 ID(개별 삭제에 사용)", example = "12") Long searchHistoryId,
			@Schema(description = "검색어", example = "김환기") String keyword,
			@Schema(description = "마지막으로 검색한 시각", example = "2026-07-28T19:24:11") String searchedAt) {

		public static ItemResponse from(SearchHistoryResult.Item item) {
			return new ItemResponse(item.searchHistoryId(), item.keyword(), item.searchedAt().toString());
		}
	}

	/** 기록 응답 — 목록을 다시 부르지 않고도 화면을 갱신할 수 있게 저장된 항목을 돌려준다. */
	public record RecordedResponse(
			@Schema(description = "검색 기록 ID", example = "12") Long searchHistoryId,
			@Schema(description = "정규화된 검색어", example = "김환기") String keyword) {

		public static RecordedResponse from(SearchHistoryResult.Recorded result) {
			return new RecordedResponse(result.searchHistoryId(), result.keyword());
		}
	}

	/** 전체 삭제 응답 — 지운 건수. */
	public record DeletedAllResponse(
			@Schema(description = "삭제된 기록 수", example = "7") long deletedCount) {
	}
}
