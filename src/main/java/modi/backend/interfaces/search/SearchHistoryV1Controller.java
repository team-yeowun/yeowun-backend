package modi.backend.interfaces.search;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import modi.backend.application.search.SearchHistoryCriteria;
import modi.backend.application.search.SearchHistoryFacade;
import modi.backend.application.search.SearchHistoryResult;
import modi.backend.interfaces.auth.Authentication;
import modi.backend.interfaces.auth.LoginUser;
import modi.backend.interfaces.search.dto.SearchHistoryDto;
import modi.backend.support.response.ApiResponse;

/**
 * 전시 검색 기록 API. 내 것만 다루므로 경로는 {@code /users/me} 하위이고 모두 인증 필수다.
 * 기록은 검색 조회가 아니라 이 경로로 들어온다(검색이 확정된 시점에 클라이언트가 호출).
 * (프로젝트 컨벤션: 성공 200 — 생성도 200으로 응답.)
 */
@RestController
@RequestMapping("/api/v1/users/me/search-history")
@RequiredArgsConstructor
public class SearchHistoryV1Controller implements SearchHistoryV1ApiSpec {

	private final SearchHistoryFacade searchHistoryFacade;

	/** 최근 검색어 목록(최신순, 최대 10). */
	@Override
	@GetMapping
	public ResponseEntity<ApiResponse<SearchHistoryDto.RecentListResponse>> getRecent(
			@Authentication LoginUser user) {
		SearchHistoryResult.RecentList result = searchHistoryFacade.getRecent(user.userId());
		return ResponseEntity.ok(ApiResponse.success(SearchHistoryDto.RecentListResponse.from(result)));
	}

	/** 검색어 기록(멱등 — 같은 검색어는 시각만 갱신돼 맨 위로 올라간다). */
	@Override
	@PostMapping
	public ResponseEntity<ApiResponse<SearchHistoryDto.RecordedResponse>> record(
			@Authentication LoginUser user,
			@Valid @RequestBody SearchHistoryDto.RecordRequest request) {
		SearchHistoryResult.Recorded result = searchHistoryFacade.record(
				new SearchHistoryCriteria.Record(user.userId(), request.keyword()));
		return ResponseEntity.ok(ApiResponse.success(SearchHistoryDto.RecordedResponse.from(result)));
	}

	/** 개별 삭제(멱등). 타인의 기록이면 403. */
	@Override
	@DeleteMapping("/{searchHistoryId}")
	public ResponseEntity<ApiResponse<Void>> delete(
			@Authentication LoginUser user,
			@PathVariable Long searchHistoryId) {
		searchHistoryFacade.delete(new SearchHistoryCriteria.Delete(user.userId(), searchHistoryId));
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	/** 전체 삭제(멱등). */
	@Override
	@DeleteMapping
	public ResponseEntity<ApiResponse<SearchHistoryDto.DeletedAllResponse>> deleteAll(
			@Authentication LoginUser user) {
		long deleted = searchHistoryFacade.deleteAll(user.userId());
		return ResponseEntity.ok(ApiResponse.success(new SearchHistoryDto.DeletedAllResponse(deleted)));
	}
}
