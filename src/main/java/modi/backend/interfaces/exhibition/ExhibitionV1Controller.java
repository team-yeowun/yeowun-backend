package modi.backend.interfaces.exhibition;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.ExhibitionCriteria;
import modi.backend.application.exhibition.ExhibitionFacade;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.interfaces.auth.Authentication;
import modi.backend.interfaces.auth.LoginUser;
import modi.backend.interfaces.auth.OptionalAuthentication;
import modi.backend.interfaces.common.dto.CursorResponse;
import modi.backend.interfaces.exhibition.dto.ExhibitionDto;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;
import modi.backend.support.response.ApiResponse;

/**
 * 전시 API(03_전시.md). 목록·상세는 공개(로그인 시 CUSTOM·개인화 반영 = 선택 인증), 개인 전시 등록은 인증 필수.
 * 목록은 커서 페이지네이션(CursorResponse). (프로젝트 컨벤션: 성공 200 — 등록도 200으로 응답.)
 */
@RestController
@RequestMapping("/api/v1/exhibitions")
@RequiredArgsConstructor
public class ExhibitionV1Controller implements ExhibitionV1ApiSpec {

	private final ExhibitionFacade exhibitionFacade;

	/** 목록/탐색. keyword·section·period·region·category·date 필터 + sort + lat/lng(distance) + 커서 페이지네이션. */
	@Override
	@GetMapping
	public ResponseEntity<ApiResponse<CursorResponse<ExhibitionDto.ListItemResponse>>> list(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String section,
			@RequestParam(required = false) String period,
			@RequestParam(required = false) String region,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String date,
			@RequestParam(defaultValue = "latest") String sort,
			@RequestParam(required = false) Double lat,
			@RequestParam(required = false) Double lng,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size,
			@OptionalAuthentication Optional<LoginUser> loginUser
    ) {
		ExhibitionCriteria.Search criteria = new ExhibitionCriteria.Search(
				blankToNull(keyword), blankToNull(section), blankToNull(period), blankToNull(region),
				blankToNull(category), parseDate(date), sort, lat, lng, blankToNull(cursor), size,
				requesterId(loginUser));

		CursorResponse<ExhibitionDto.ListItemResponse> data = ExhibitionDto.ListCut.of(size)
				.flatMap(cut -> cutFromCachedFirstPage(criteria, cut))
				.orElseGet(() -> ExhibitionDto.listPage(exhibitionFacade.search(criteria)));
		return ResponseEntity.ok(ApiResponse.success(data));
	}

	/**
	 * - 작은 첫 페이지를 캐시된 기본 크기 페이지에서 잘라 준다(홈 섹션 size=2·5 등)
	 *   - 같은 조회를 기본 크기로 바꿨을 때 캐시가 서빙하는 경우에만 함 — 그 판정은 파사드(캐시 리졸버)가 한다
	 *   - 캐시 대상이 아니거나 자를 수 없으면 빈 값 → 원래 크기로 그대로 읽는다
	 */
	private Optional<CursorResponse<ExhibitionDto.ListItemResponse>> cutFromCachedFirstPage(
			ExhibitionCriteria.Search criteria, ExhibitionDto.ListCut cut) {
		ExhibitionCriteria.Search defaultPage = criteria.withSize(null);
		if (!exhibitionFacade.servesFromCache(defaultPage)) {
			return Optional.empty();
		}
		return cut.cut(exhibitionFacade.search(defaultPage));
	}

	/**
	 * 같은 필터의 전시 총 건수. 목록과 <b>같은 필터 파라미터</b>를 받아 같은 조건 조립 경로를 탄다
	 * (정렬·커서·페이지 크기·좌표는 총 건수와 무관해 받지 않는다).
	 */
	@Override
	@GetMapping("/count")
	public ResponseEntity<ApiResponse<ExhibitionDto.CountResponse>> count(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String section,
			@RequestParam(required = false) String period,
			@RequestParam(required = false) String region,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String date,
			@OptionalAuthentication Optional<LoginUser> loginUser) {
		// 목록과 같은 Criteria를 쓴다 — 파라미터 목록을 복사하지 않아야 두 경로의 필터가 어긋나지 않는다.
		ExhibitionCriteria.Search criteria = new ExhibitionCriteria.Search(
				blankToNull(keyword), blankToNull(section), blankToNull(period), blankToNull(region),
				blankToNull(category), parseDate(date), null, null, null, null, null,
				requesterId(loginUser));
		return ResponseEntity.ok(ApiResponse.success(
				ExhibitionDto.CountResponse.from(exhibitionFacade.count(criteria))));
	}

	/** 홈 배너(E-10). 오늘 진행 중인 전시 중 최근 3일 조회수 상위 최대 3개. 공개(인증 불필요). */
	@Override
	@GetMapping("/banners")
	public ResponseEntity<ApiResponse<ExhibitionDto.BannersResponse>> banners() {
		return ResponseEntity.ok(ApiResponse.success(
				ExhibitionDto.BannersResponse.from(exhibitionFacade.banners())));
	}

	/** 지역 필터 그룹(전시탐색 필터 시트 칩). 정적 메타데이터. 공개(인증 불필요). */
	@Override
	@GetMapping("/region-groups")
	public ResponseEntity<ApiResponse<ExhibitionDto.RegionGroupsResponse>> regionGroups() {
		return ResponseEntity.ok(ApiResponse.success(
				ExhibitionDto.RegionGroupsResponse.from(exhibitionFacade.getRegionGroups())));
	}

	/** 상세. CATALOG 공개 / CUSTOM은 등록자 본인만(타인 접근 403). */
	@Override
	@GetMapping("/{exhibitionId}")
	public ResponseEntity<ApiResponse<ExhibitionDto.DetailResponse>> detail(
			@PathVariable Long exhibitionId,
			@OptionalAuthentication Optional<LoginUser> loginUser) {
		ExhibitionResult.Detail result = exhibitionFacade.getDetail(
				new ExhibitionCriteria.Detail(exhibitionId, requesterId(loginUser)));
		return ResponseEntity.ok(ApiResponse.success(ExhibitionDto.DetailResponse.from(result)));
	}

	/** 개인 전시 등록. 인증 필수. 제목 필수·기간 규칙은 도메인에서 검증. */
	@Override
	@PostMapping("/custom")
	public ResponseEntity<ApiResponse<ExhibitionDto.CreatedResponse>> registerCustom(
			@Authentication LoginUser user,
			@Valid @RequestBody ExhibitionDto.CustomCreateRequest request) {
		ExhibitionResult.Created result = exhibitionFacade.registerCustom(new ExhibitionCriteria.CustomCreate(
				user.userId(), request.title(), request.venueId(), request.place(), parseDate(request.startDate()),
				parseDate(request.endDate()), request.region(), request.category(), request.format(),
				request.artist(), request.posterUrl(), request.genreKeyword()));
		return ResponseEntity.ok(ApiResponse.success(ExhibitionDto.CreatedResponse.from(result)));
	}

	/**
	 * - 빈 문자열 파라미터를 "보내지 않음"으로 바꾼다
	 *   - 프론트 탐색 화면은 검색어가 비어 있어도 {@code keyword=}를 붙여 보낸다(axios는 null·undefined만 뺀다)
	 *   - 조회 조건으로는 이미 빈 값 = 없음이지만, 캐시 판정({@code isPlainFirstPage})은 {@code ""}를 필터로 봐 캐시를 건너뛴다
	 *   - 그래서 Criteria에 싣기 전에 여기서 맞춘다. 1글자 검색어처럼 내용이 있는 값의 검증은 그대로 뒤에서 한다
	 */
	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static Long requesterId(Optional<LoginUser> loginUser) {
		return loginUser.map(LoginUser::userId).orElse(null);
	}

	/** YYYY-MM-DD 문자열 → LocalDate. 빈 값은 null, 형식 오류는 400 INVALID_INPUT. */
	private static LocalDate parseDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new CoreException(ErrorType.INVALID_INPUT, "날짜 형식은 YYYY-MM-DD여야 합니다: " + value);
		}
	}
}
