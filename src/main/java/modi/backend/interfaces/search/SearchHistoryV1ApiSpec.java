package modi.backend.interfaces.search;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.interfaces.auth.LoginUser;
import modi.backend.interfaces.search.dto.SearchHistoryDto;

/**
 * 전시 검색 기록 API Swagger 스펙. MVC 어노테이션은 {@link SearchHistoryV1Controller}.
 * 모두 인증 필수이며, 기록·삭제는 멱등이다.
 */
@Tag(name = "SearchHistory", description = "전시 검색 기록(최근 검색어). 회원 전용 — 모두 인증 필수.")
public interface SearchHistoryV1ApiSpec {

	@Operation(summary = "최근 검색어 목록",
			description = "내 최근 검색어를 최신순으로 최대 10개 반환한다. 기록이 없으면 빈 배열. access 토큰 필요.")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = SearchHistoryDto.RecentListResponse.class),
					examples = @ExampleObject(value = """
							{
							  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
							  "data": { "content": [
							    { "searchHistoryId": 12, "keyword": "김환기", "searchedAt": "2026-07-28T19:24:11" },
							    { "searchHistoryId": 9,  "keyword": "리움", "searchedAt": "2026-07-28T18:02:40" }
							  ] }
							}
							"""))),
			@ApiResponse(responseCode = "401", description = "UNAUTHORIZED — 미인증", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE)),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<SearchHistoryDto.RecentListResponse>> getRecent(
			@Parameter(hidden = true) LoginUser user);

	@Operation(summary = "검색어 기록",
			description = """
					검색이 확정된 시점(엔터·검색 버튼)에 호출한다. 목록 조회 API는 기록을 남기지 않는다 —
					타이핑 디바운스로 검색하면 중간 입력까지 쌓이기 때문이다.

					멱등이다: 같은 검색어가 이미 있으면 새로 만들지 않고 검색 시각만 갱신해 맨 위로 올린다.
					10개를 넘는 오래된 기록은 저장 시점에 정리된다.""")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기록 성공(멱등)", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = SearchHistoryDto.RecordedResponse.class),
					examples = @ExampleObject(value = """
							{
							  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
							  "data": { "searchHistoryId": 12, "keyword": "김환기" }
							}
							"""))),
			@ApiResponse(responseCode = "400", description = "INVALID_INPUT — 2글자 미만이거나 100글자 초과",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							examples = @ExampleObject(value = """
									{ "meta": { "result": "FAIL", "errorCode": "INVALID_INPUT", "message": "검색어는 최소 2글자여야 합니다: 김" }, "data": null }
									"""))),
			@ApiResponse(responseCode = "401", description = "UNAUTHORIZED — 미인증", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE)),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<SearchHistoryDto.RecordedResponse>> record(
			@Parameter(hidden = true) LoginUser user,
			SearchHistoryDto.RecordRequest request);

	@Operation(summary = "검색어 개별 삭제",
			description = "검색 기록 하나를 지운다(멱등 — 이미 없어도 200). 타인의 기록이면 403.")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "삭제 성공(멱등)", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					examples = @ExampleObject(value = """
							{ "meta": { "result": "SUCCESS", "errorCode": null, "message": null }, "data": null }
							"""))),
			@ApiResponse(responseCode = "403", description = "FORBIDDEN — 타인의 기록", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE)),
			@ApiResponse(responseCode = "401", description = "UNAUTHORIZED — 미인증", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE)),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<Void>> delete(
			@Parameter(hidden = true) LoginUser user,
			@Parameter(description = "검색 기록 ID", example = "12") Long searchHistoryId);

	@Operation(summary = "검색어 전체 삭제", description = "내 검색 기록을 모두 지운다(멱등). 지운 건수를 반환한다.")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "삭제 성공(멱등)", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = SearchHistoryDto.DeletedAllResponse.class),
					examples = @ExampleObject(value = """
							{
							  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
							  "data": { "deletedCount": 7 }
							}
							"""))),
			@ApiResponse(responseCode = "401", description = "UNAUTHORIZED — 미인증", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE)),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<SearchHistoryDto.DeletedAllResponse>> deleteAll(
			@Parameter(hidden = true) LoginUser user);
}
