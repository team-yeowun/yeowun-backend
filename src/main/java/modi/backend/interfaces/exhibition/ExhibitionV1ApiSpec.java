package modi.backend.interfaces.exhibition;

import java.util.Optional;

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
import modi.backend.interfaces.common.dto.CursorResponse;
import modi.backend.interfaces.exhibition.dto.ExhibitionDto;

/**
 * 전시 API Swagger 스펙(03_전시.md). MVC 어노테이션은 {@link ExhibitionV1Controller}.
 */
@Tag(name = "Exhibition", description = "전시 목록/탐색 · 상세 · 개인 전시 등록.")
public interface ExhibitionV1ApiSpec {

	@Operation(summary = "전시 목록/탐색", description = """
			필터·정렬·커서로 전시를 조회한다(커서 페이지네이션). 필터 미지정 시 오늘 진행 중인 전시를 기본 노출한다.
			이 응답에는 총 건수(totalCount)가 없다 — 숫자가 필요하면 같은 필터로 GET /exhibitions/count를 병렬 호출한다
			(목록이 총 건수를 기다리지 않고 먼저 뜬다).
			비로그인은 CATALOG만, 로그인은 CATALOG + 본인 CUSTOM을 함께 본다(로그인 시 bookmarked 개인화).
			인증은 선택(Optional)이다 — Authorization 헤더가 없거나 토큰이 무효해도 401을 내지 않고
			비로그인(익명)으로 취급해 조회를 계속한다.
			정렬이 바뀌면 커서를 버리고 처음부터 재조회한다(커서의 정렬 판별자와 sort가 다르면 INVALID_CURSOR).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = CursorResponse.class),
					examples = @ExampleObject(name = "목록 조회 성공", value = """
							{
							  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
							  "data": {
							    "content": [
							      {
							        "exhibitionId": 51,
							        "type": "CATALOG",
							        "title": "모네: 빛을 그리다",
							        "posterUrl": "https://cdn.modi.app/exhibitions/51/poster.jpg",
							        "startDate": "2026-06-01",
							        "endDate": "2026-08-31",
							        "place": "예술의전당 한가람미술관",
							        "region": "SEOUL",
							        "category": "PAINTING",
							        "artistSummary": null,
							        "dDay": 5,
							        "free": false,
							        "bookmarked": false
							      }
							    ],
							    "nextCursor": "eyJzb3J0IjoibGF0ZXN0IiwibGFzdElkIjo1MX0",
							    "hasNext": true
							  }
							}
							"""))),
			@ApiResponse(responseCode = "400", description = "INVALID_INPUT — keyword 1글자, sort=distance인데 lat·lng 없음, "
					+ "미정의 region/category/section 코드, date 형식 오류 / INVALID_CURSOR — 커서-정렬 불일치·손상",
					content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					examples = @ExampleObject(name = "거리순인데 좌표 없음", value = """
							{
							  "meta": { "result": "FAIL", "errorCode": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다." },
							  "data": null
							}
							"""))),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<CursorResponse<ExhibitionDto.ListItemResponse>>> list(
			@Parameter(description = "전시명·전시장명 부분 일치(최소 2글자)", example = "모네") String keyword,
			@Parameter(description = "섹션 필터", schema = @Schema(allowableValues = { "ending-soon",
					"opening-this-month", "free" }), example = "ending-soon") String section,
			@Parameter(description = "opening-this-month 기간 범위(기본 month)",
					schema = @Schema(allowableValues = { "month", "week" }), example = "month") String period,
			@Parameter(description = "지역 코드 콤마 다중(SEOUL,GYEONGGI 등)", example = "SEOUL,GYEONGGI") String region,
			@Parameter(description = "카테고리 코드 콤마 다중(PAINTING·PHOTO·MEDIA·SCULPTURE·DESIGN·CRAFT·"
					+ "ARCHITECTURE·PERFORMANCE·ETC)", example = "PHOTO,MEDIA") String category,
			@Parameter(description = "해당 날짜에 진행 중인 전시(YYYY-MM-DD)", example = "2026-06-30") String date,
			@Parameter(description = "정렬 코드. latest=시작일 최신순(기본), ending=종료일 임박순, popular=조회수 많은순, "
					+ "distance=거리순(lat·lng 필수)", example = "latest",
					schema = @Schema(allowableValues = { "latest", "ending", "popular", "distance" })) String sort,
			@Parameter(description = "위도(sort=distance 필수)", example = "37.5033") Double lat,
			@Parameter(description = "경도(sort=distance 필수)", example = "126.9575") Double lng,
			@Parameter(description = "다음 페이지 조회용 opaque 커서(첫 페이지는 생략)") String cursor,
			@Parameter(description = "페이지 크기(기본 20, 최대 50)", example = "20") Integer size,
			@Parameter(hidden = true) Optional<LoginUser> loginUser);

	@Operation(summary = "전시 총 건수", description = """
			목록과 같은 필터 조건의 전시 총 건수를 반환한다. 목록(GET /exhibitions)에서 분리된 엔드포인트다.
			필터 시트("126개 전시 보기")는 목록 없이 이것만 호출하고, 목록 헤더("전시 20")는 목록과 이것을 병렬 호출한다
			— 그래야 목록이 총 건수를 기다리지 않고 먼저 뜬다.
			필터 파라미터는 목록과 동일하며(keyword·section·period·region·category·date), 서버가 목록과 같은
			조건 조립 경로를 공유하므로 두 응답의 필터 해석이 어긋나지 않는다.
			정렬(sort)·커서(cursor)·페이지 크기(size)·좌표(lat/lng)는 총 건수와 무관해 받지 않는다.
			인증은 선택(Optional) — 로그인 시 본인 CUSTOM 전시가 목록과 동일하게 집계에 포함된다.
			exact는 현재 항상 true다(정확한 count). 나중에 상한 근사로 바뀌면 이 값이 false가 될 수 있다.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ExhibitionDto.CountResponse.class),
					examples = @ExampleObject(name = "총 건수 조회 성공", value = """
							{
							  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
							  "data": { "count": 126, "exact": true }
							}
							"""))),
			@ApiResponse(responseCode = "400", description = "INVALID_INPUT — keyword 1글자, 미정의 region/category/section 코드, "
					+ "date 형식 오류(목록과 같은 검증 규칙)",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							examples = @ExampleObject(name = "검색어가 1글자", value = """
									{
									  "meta": { "result": "FAIL", "errorCode": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다." },
									  "data": null
									}
									"""))),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<ExhibitionDto.CountResponse>> count(
			@Parameter(description = "전시명·전시장명 부분 일치(최소 2글자)", example = "모네") String keyword,
			@Parameter(description = "섹션 필터", schema = @Schema(allowableValues = { "ending-soon",
					"opening-this-month", "free" }), example = "ending-soon") String section,
			@Parameter(description = "opening-this-month 기간 범위(기본 month)",
					schema = @Schema(allowableValues = { "month", "week" }), example = "month") String period,
			@Parameter(description = "지역 코드 콤마 다중(SEOUL,GYEONGGI 등)", example = "SEOUL,GYEONGGI") String region,
			@Parameter(description = "카테고리 코드 콤마 다중(PAINTING·PHOTO·MEDIA·SCULPTURE·DESIGN·CRAFT·"
					+ "ARCHITECTURE·PERFORMANCE·ETC)", example = "PHOTO,MEDIA") String category,
			@Parameter(description = "해당 날짜에 진행 중인 전시(YYYY-MM-DD)", example = "2026-06-30") String date,
			@Parameter(hidden = true) Optional<LoginUser> loginUser);

	@Operation(summary = "홈 배너 조회", description = """
			홈 상단 캐러셀용 배너를 최대 3개 조회한다(03_전시.md E-10). 공개 API(인증 불필요).
			현재는 오늘 진행 중인 전시 중 조회수 상위 최대 3개를 노출한다(운영자 지정 기능은 추후).
			진행 중 전시가 없으면 data.banners는 빈 배열이다.
			홈 화면은 이 배너 1콜과 섹션 조회(GET /exhibitions?section=...) 3콜을 병렬로 호출한다.""")
	@ApiResponses(@ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(
			mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = ExhibitionDto.BannersResponse.class),
			examples = @ExampleObject(name = "배너 조회 성공", value = """
					{
					  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
					  "data": {
					    "banners": [
					      {
					        "exhibitionId": 51,
					        "title": "모네: 빛을 그리다",
					        "bannerImageUrl": "https://cdn.modi.app/exhibitions/51/poster.jpg",
					        "startDate": "2026-06-01",
					        "endDate": "2026-08-31",
					        "place": "예술의전당 한가람미술관"
					      }
					    ]
					  }
					}"""))))
	ResponseEntity<modi.backend.support.response.ApiResponse<ExhibitionDto.BannersResponse>> banners();

	@Operation(summary = "지역 필터 그룹 조회", description = """
			전시탐색 필터 시트의 지역 칩 목록(디자인 병합 그룹)을 조회한다. 공개 API(인증 불필요).
			칩 1개 = 그룹 1개이며, 목록 검색 시 선택한 그룹들의 regions를 콤마로 이어
			GET /exhibitions의 region 파라미터에 그대로 넣는다(예: region=GYEONGGI,INCHEON).
			그룹 구성은 서버가 단일 소스로 관리한다 — 클라이언트는 이 응답을 그대로 렌더링한다.""")
	@ApiResponses(@ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(
			mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = ExhibitionDto.RegionGroupsResponse.class),
			examples = @ExampleObject(name = "지역 그룹 조회 성공", value = """
					{
					  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
					  "data": {
					    "groups": [
					      { "code": "SEOUL", "label": "서울", "regions": ["SEOUL"] },
					      { "code": "GYEONGGI_INCHEON", "label": "경기·인천", "regions": ["GYEONGGI", "INCHEON"] },
					      { "code": "GANGWON", "label": "강원", "regions": ["GANGWON"] },
					      { "code": "DAEJEON_SEJONG_CHUNGCHEONG", "label": "대전·세종·충청",
					        "regions": ["DAEJEON", "SEJONG", "CHUNGNAM", "CHUNGBUK"] },
					      { "code": "GWANGJU_JEOLLA", "label": "광주·전라",
					        "regions": ["GWANGJU", "JEONNAM", "JEONBUK"] },
					      { "code": "DAEGU_GYEONGBUK", "label": "대구·경북", "regions": ["DAEGU", "GYEONGBUK"] },
					      { "code": "BUSAN_ULSAN_GYEONGNAM", "label": "부산·울산·경남",
					        "regions": ["BUSAN", "ULSAN", "GYEONGNAM"] },
					      { "code": "JEJU", "label": "제주", "regions": ["JEJU"] },
					      { "code": "ETC", "label": "기타", "regions": ["ETC"] }
					    ]
					  }
					}"""))))
	ResponseEntity<modi.backend.support.response.ApiResponse<ExhibitionDto.RegionGroupsResponse>> regionGroups();

	@Operation(summary = "전시 상세", description = """
			CATALOG는 공개, CUSTOM은 등록자 본인만 조회 가능. 인증은 선택(Optional) —
			비로그인·무효 토큰이어도 CATALOG 전시는 정상 조회된다. 로그인 시 bookmarked·recorded 개인화 필드를 채운다.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ExhibitionDto.DetailResponse.class),
					examples = @ExampleObject(name = "상세 조회 성공", value = """
							{
							  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
							  "data": {
							    "exhibitionId": 51,
							    "type": "CATALOG",
							    "title": "모네: 빛을 그리다",
							    "posterUrl": "https://cdn.modi.app/exhibitions/51/poster.jpg",
							    "startDate": "2026-06-01",
							    "endDate": "2026-08-31",
							    "place": "예술의전당 한가람미술관",
							    "region": "SEOUL",
							    "category": "PAINTING",
							    "description": "인상주의 거장 모네의 대표작을 만나는 특별전.",
							    "operatingHours": "매일 10:00 ~ 18:00",
							    "price": "무료",
							    "artists": ["클로드 모네"],
							    "keywords": ["인상주의", "회화"],
							    "serviceName": "문화포털",
							    "detailUrl": "https://culture.go.kr/exhibitions/51",
							    "gpsX": 127.0136,
							    "gpsY": 37.4783,
							    "address": "서울특별시 서초구 남부순환로 2406",
							    "imgUrl": "https://cdn.modi.app/exhibitions/51/detail.jpg",
							    "phone": "02-580-1300",
							    "viewCount": 1024,
							    "sigungu": "서초구",
							    "placeUrl": "https://www.sac.or.kr",
							    "artistSummary": null,
							    "free": true,
							    "bookmarked": true,
							    "recorded": false
							  }
							}
							"""))),
			@ApiResponse(responseCode = "403", description = "FORBIDDEN — 타인이 등록한 CUSTOM 전시에 접근",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							examples = @ExampleObject(name = "타인의 CUSTOM 전시 접근", value = """
									{
									  "meta": { "result": "FAIL", "errorCode": "FORBIDDEN", "message": "접근 권한이 없습니다." },
									  "data": null
									}
									"""))),
			@ApiResponse(responseCode = "404", description = "NOT_FOUND — 요청한 exhibitionId의 전시가 존재하지 않음",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							examples = @ExampleObject(name = "존재하지 않는 전시", value = """
									{
									  "meta": { "result": "FAIL", "errorCode": "NOT_FOUND", "message": "요청한 전시를 찾을 수 없습니다." },
									  "data": null
									}
									"""))),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<ExhibitionDto.DetailResponse>> detail(
			@Parameter(description = "전시 ID", example = "51") Long exhibitionId,
			@Parameter(hidden = true) Optional<LoginUser> loginUser);

	@Operation(summary = "개인 전시 등록", description = "카탈로그에 없는 개인 전시를 직접 등록한다. access 토큰 필요(인증 필수).")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "등록 성공(프로젝트 컨벤션상 201 대신 200 사용)",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ExhibitionDto.CreatedResponse.class),
							examples = @ExampleObject(name = "등록 성공", value = """
									{
									  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
									  "data": { "exhibitionId": 108, "type": "CUSTOM" }
									}
									"""))),
			@ApiResponse(responseCode = "400", description = "INVALID_INPUT — 제목 공백, 종료일이 시작일보다 빠름, "
					+ "미정의 region/category 코드 등. Bean Validation 실패 시 필드별 오류가 data에 배열로 담긴다.",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							examples = {
									@ExampleObject(name = "제목 공백(필드 검증 실패)", value = """
											{
											  "meta": { "result": "FAIL", "errorCode": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다." },
											  "data": [
											    { "field": "title", "value": "", "reason": "공백일 수 없습니다" }
											  ]
											}
											"""),
									@ExampleObject(name = "종료일이 시작일보다 빠름(도메인 규칙 위반)", value = """
											{
											  "meta": { "result": "FAIL", "errorCode": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다." },
											  "data": null
											}
											"""),
							})),
			@ApiResponse(responseCode = "401", description = "UNAUTHORIZED — Bearer access 토큰이 없거나 무효함",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							examples = @ExampleObject(name = "토큰 없음/무효", value = """
									{
									  "meta": { "result": "FAIL", "errorCode": "UNAUTHORIZED", "message": "인증이 필요합니다." },
									  "data": null
									}
									"""))),
	})
	ResponseEntity<modi.backend.support.response.ApiResponse<ExhibitionDto.CreatedResponse>> registerCustom(
			@Parameter(hidden = true) LoginUser user,
			ExhibitionDto.CustomCreateRequest request);

}
