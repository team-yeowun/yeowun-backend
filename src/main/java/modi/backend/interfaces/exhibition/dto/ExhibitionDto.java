package modi.backend.interfaces.exhibition.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import modi.backend.application.exhibition.ExhibitionResult;

/**
 * 전시 API 요청/응답 DTO 모음(파일 수 절감을 위해 중첩 record로 묶음).
 * 형식 검증은 Bean Validation, 날짜/enum 변환·도메인 규칙은 Controller·Facade·Entity에서 판단한다.
 */
public final class ExhibitionDto {

	private ExhibitionDto() {
	}

	/**
	 * 개인 전시(CUSTOM) 등록 요청. 제목만 필수. 날짜는 {@code YYYY-MM-DD} 문자열로 받아 Controller에서 파싱한다
	 * (파싱 실패 → 400 INVALID_INPUT). region/category 코드 검증은 Facade에서 수행한다.
	 */
	public record CustomCreateRequest(
			@NotBlank @Size(max = 100)
			@Schema(description = "전시 제목. 필수, 공백 불가.", example = "친구와 다녀온 사진전")
			String title,
			@Schema(description = "전시관 ID(전시관 검색 선택). 지정 시 place·region은 전시관 값으로 파생하고, 없는 ID면 404 VENUE_NOT_FOUND. "
					+ "venueId와 place는 둘 중 하나.", example = "7", nullable = true)
			Long venueId,
			@Size(max = 200)
			@Schema(description = "전시 장소명(직접 입력). 선택. venueId가 있으면 무시된다.", example = "성수 갤러리", nullable = true)
			String place,
			@Schema(description = "시작일(YYYY-MM-DD 문자열). Controller에서 LocalDate로 파싱하며, "
					+ "형식이 올바르지 않으면 400 INVALID_INPUT.", example = "2026-06-20", nullable = true)
			String startDate,
			@Schema(description = "종료일(YYYY-MM-DD 문자열). startDate보다 빠르면 400 INVALID_INPUT(도메인 규칙).",
					example = "2026-06-30", nullable = true)
			String endDate,
			@Schema(description = "지역 코드. 정의되지 않은 코드는 400 INVALID_INPUT.",
					example = "SEOUL", allowableValues = { "SEOUL", "GYEONGGI", "INCHEON", "DAEGU", "GYEONGBUK",
							"BUSAN", "ULSAN", "GYEONGNAM", "SEJONG", "JEONNAM", "JEONBUK", "JEJU", "CHUNGNAM",
							"CHUNGBUK", "ETC" }, nullable = true)
			String region,
			@Schema(description = "카테고리 코드(회화·사진 등 매체). 정의되지 않은 코드는 400 INVALID_INPUT.",
					example = "PHOTO", allowableValues = { "PAINTING", "PHOTO", "MEDIA", "SCULPTURE", "DESIGN",
							"CRAFT", "ARCHITECTURE", "PERFORMANCE", "ETC" }, nullable = true)
			String category,
			@Schema(description = "전시 형태. 정의되지 않은 코드는 400 INVALID_INPUT. SOLO=개인전, GROUP=단체전, "
					+ "CURATED=기획전, ART_FAIR=아트페어.", example = "SOLO",
					allowableValues = { "SOLO", "GROUP", "CURATED", "ART_FAIR" }, nullable = true)
			String format,
			@Size(max = 100)
			@Schema(description = "참여 작가·주최명. 선택.", example = "김선영", nullable = true)
			String artist,
			@Size(max = 2048)
			@Schema(description = "포스터 이미지 URL. 선택.", example = "https://cdn.modi.app/exhibitions/108/poster.jpg",
					nullable = true)
			String posterUrl,
			@Size(max = 50)
			@Schema(description = "장르 키워드(마스터 10종 중 1개, 와이어프레임 장르 시트). 선택 — 미지정 시 분류기(AI/랜덤)가 자동 부여. "
					+ "마스터 밖 값은 400 INVALID_INPUT.",
					example = "사진", allowableValues = { "회화·드로잉", "사진", "미디어아트", "조각·설치", "디자인",
							"공예", "건축", "공연", "현대미술", "일러스트레이션" }, nullable = true)
			String genreKeyword) {
	}

	/** 목록 항목(5.2 content[], 공통 스키마 ExhibitionListItem). */
	public record ListItemResponse(
			@Schema(description = "전시 ID", example = "51") Long exhibitionId,
			@Schema(description = "전시 출처. CATALOG=외부 공개 전시 API 수집, CUSTOM=사용자 직접 등록",
					example = "CATALOG", allowableValues = { "CATALOG", "CUSTOM" }) String type,
			@Schema(description = "전시 제목", example = "모네: 빛을 그리다") String title,
			@Schema(description = "포스터 이미지 URL. 없으면 null.", example = "https://cdn.modi.app/exhibitions/51/poster.jpg",
					nullable = true) String posterUrl,
			@Schema(description = "시작일", example = "2026-06-01") LocalDate startDate,
			@Schema(description = "종료일", example = "2026-08-31") LocalDate endDate,
			@Schema(description = "전시 장소명", example = "예술의전당 한가람미술관") String place,
			@Schema(description = "지역 코드", example = "SEOUL") String region,
			@Schema(description = "카테고리 코드", example = "PAINTING") String category,
			@Schema(description = "작가 요약. CUSTOM은 등록 작가명, CATALOG는 null.", example = "김미경 외 10인",
					nullable = true) String artistSummary,
			@Schema(description = "종료 D-데이(오늘로부터 종료일까지 남은 일수). 종료일 없음/이미 종료면 null.", example = "5",
					nullable = true) Integer dDay,
			@Schema(description = "무료 여부", example = "true") boolean free,
			@Schema(description = "요청자의 관심 등록 여부(비로그인 false)", example = "false") boolean bookmarked) {

		public static ListItemResponse from(ExhibitionResult.ListItem result) {
			return new ListItemResponse(result.exhibitionId(), result.type(), result.title(), result.posterUrl(),
					result.startDate(), result.endDate(), result.place(), result.region(), result.category(),
					result.artistSummary(), result.dDay(), result.free(), result.bookmarked());
		}
	}

	/**
	 * 전시 상세(3.3.2). description·operatingHours·price·artists·keywords는 데이터 있을 때만(없으면 null/빈 배열).
	 * address·imgUrl·phone·viewCount·sigungu·placeUrl은 상세 지연수집(catalog detail2) 필드 — 없으면 null(일관 스키마).
	 * realmName·areaText·placeSeq 등 내부 보존 전용 필드는 UI 계약이 아니라 응답에 넣지 않는다.
	 */
	public record DetailResponse(
			@Schema(description = "전시 ID", example = "51") Long exhibitionId,
			@Schema(description = "전시 출처. CATALOG=외부 공개 전시 API 수집, CUSTOM=사용자 직접 등록",
					example = "CATALOG", allowableValues = { "CATALOG", "CUSTOM" }) String type,
			@Schema(description = "전시 제목", example = "모네: 빛을 그리다") String title,
			@Schema(description = "포스터 이미지 URL. 없으면 null.", example = "https://cdn.modi.app/exhibitions/51/poster.jpg",
					nullable = true) String posterUrl,
			@Schema(description = "시작일", example = "2026-06-01") LocalDate startDate,
			@Schema(description = "종료일", example = "2026-08-31") LocalDate endDate,
			@Schema(description = "전시 장소명", example = "예술의전당 한가람미술관") String place,
			@Schema(description = "지역 코드", example = "SEOUL") String region,
			@Schema(description = "카테고리 코드", example = "PAINTING") String category,
			@Schema(description = "전시 형태(개인 전시 등록 시). CATALOG·미지정은 null. SOLO/GROUP/CURATED/ART_FAIR.",
					example = "SOLO", nullable = true) String format,
			@Schema(description = "전시 설명. 데이터 없으면 null.",
					example = "인상주의 거장 모네의 대표작을 만나는 특별전.", nullable = true) String description,
			@Schema(description = "운영시간(구글 지도 기준, 여러 줄은 \\n 구분). 데이터 없으면 null.", example = "매일 10:00 ~ 18:00", nullable = true)
			String operatingHours,
			@Schema(description = "관람 가격 안내. 원천에 가격이 없으면 \"관람료 정보 없음\".", example = "성인 20,000원 / 청소년 15,000원")
			String price,
			@Schema(description = "참여 작가 목록. 데이터 없으면 빈 배열.", example = "[\"클로드 모네\"]") List<String> artists,
			@Schema(description = "키워드 목록. 데이터 없으면 빈 배열.", example = "[\"인상주의\", \"회화\"]") List<String> keywords,
			@Schema(description = "출처 서비스명(예: 문화포털). CUSTOM 전시는 null.", example = "문화포털", nullable = true)
			String serviceName,
			@Schema(description = "원본 상세 페이지 URL. 없으면 null.", example = "https://culture.go.kr/exhibitions/51",
					nullable = true) String detailUrl,
			@Schema(description = "GPS 경도. 없으면 null.", example = "127.0136", nullable = true) Double gpsX,
			@Schema(description = "GPS 위도. 없으면 null.", example = "37.4783", nullable = true) Double gpsY,
			@Schema(description = "상세 지연수집(catalog detail2) 필드 — 전시장 주소. 미수집 시 null.",
					example = "서울특별시 서초구 남부순환로 2406", nullable = true) String address,
			@Schema(description = "상세 지연수집 필드 — 상세 이미지 URL. 미수집 시 null.",
					example = "https://cdn.modi.app/exhibitions/51/detail.jpg", nullable = true) String imgUrl,
			@Schema(description = "상세 지연수집 필드 — 전화번호. 미수집 시 null.", example = "02-580-1300", nullable = true)
			String phone,
			@Schema(description = "상세 지연수집 필드 — 누적 조회수. 미수집 시 0.", example = "1024") long viewCount,
			@Schema(description = "상세 지연수집 필드 — 시군구. 미수집 시 null.", example = "서초구", nullable = true) String sigungu,
			@Schema(description = "상세 지연수집 필드 — 전시장 홈페이지 URL. 미수집 시 null.", example = "https://www.sac.or.kr",
					nullable = true) String placeUrl,
			@Schema(description = "작가 요약. CUSTOM은 등록 작가명, CATALOG는 null.", example = "김미경 외 10인",
					nullable = true) String artistSummary,
			@Schema(description = "무료 여부", example = "true") boolean free,
			@Schema(description = "요청자의 관심 등록 여부(비로그인 false)", example = "true") boolean bookmarked,
			@Schema(description = "요청자가 이 전시에 대한 기록 보유 여부(비로그인 false). true면 '기록하기' 버튼 분기.",
					example = "false") boolean recorded) {

		/** 원천에 가격이 없을 때 프론트가 그대로 노출할 안내 문구. 값을 지어내지 않고 "정보 없음"을 명시한다. */
		private static final String PRICE_UNKNOWN = "관람료 정보 없음";

		public static DetailResponse from(ExhibitionResult.Detail result) {
			String price = result.price() == null || result.price().isBlank() ? PRICE_UNKNOWN : result.price();
			return new DetailResponse(result.exhibitionId(), result.type(), result.title(), result.posterUrl(),
					result.startDate(), result.endDate(), result.place(), result.region(), result.category(),
					result.format(),
					result.description(), result.operatingHours(), price, result.artists(),
					result.keywords(), result.serviceName(), result.detailUrl(), result.gpsX(), result.gpsY(),
					result.address(), result.imgUrl(), result.phone(), result.viewCount(), result.sigungu(),
					result.placeUrl(), result.artistSummary(), result.free(), result.bookmarked(),
					result.recorded());
		}
	}

	/**
	 * 전시 총 건수(필터 시트 "126개 전시 보기" · 목록 헤더 "전시 20"). 목록과 같은 필터 파라미터를 받는다.
	 * {@code exact}는 현재 항상 true다 — 나중에 상한 근사로 갈아탈 때 프론트 재작업이 없도록 자리를 미리 잡아 둔 필드다.
	 */
	public record CountResponse(
			@Schema(description = "필터 조건에 맞는 전시 총 건수", example = "126") long count,
			@Schema(description = "count가 정확한 값인지 여부. 현재 구현은 항상 true(정확한 count).", example = "true")
			boolean exact) {

		public static CountResponse from(ExhibitionResult.Count result) {
			return new CountResponse(result.count(), result.exact());
		}
	}

	/** 홈 배너 목록(E-10). data.banners 배열로 최대 3개. 진행 중 전시가 없으면 빈 배열. */
	public record BannersResponse(
			@Schema(description = "홈 배너 목록(최대 3개). 진행 중 전시가 없으면 빈 배열.") List<BannerResponse> banners) {

		public static BannersResponse from(List<ExhibitionResult.Banner> banners) {
			return new BannersResponse(banners.stream().map(BannerResponse::from).toList());
		}
	}

	/** 홈 배너 항목(E-10). 배너 이미지는 전시 포스터를 사용한다. */
	public record BannerResponse(
			@Schema(description = "전시 ID", example = "51") Long exhibitionId,
			@Schema(description = "전시 제목", example = "모네: 빛을 그리다") String title,
			@Schema(description = "배너 이미지 URL(전시 포스터). 없으면 null.",
					example = "https://cdn.modi.app/exhibitions/51/poster.jpg", nullable = true) String bannerImageUrl,
			@Schema(description = "시작일", example = "2026-06-01") LocalDate startDate,
			@Schema(description = "종료일", example = "2026-08-31") LocalDate endDate,
			@Schema(description = "전시 장소명", example = "예술의전당 한가람미술관") String place) {

		public static BannerResponse from(ExhibitionResult.Banner result) {
			return new BannerResponse(result.exhibitionId(), result.title(), result.bannerImageUrl(),
					result.startDate(), result.endDate(), result.place());
		}
	}

	/** 지역 필터 그룹 목록(전시탐색 필터 시트의 병합 칩). data.groups 배열. */
	public record RegionGroupsResponse(
			@Schema(description = "지역 필터 그룹 목록(디자인 칩 순서 그대로).") List<RegionGroupResponse> groups) {

		public static RegionGroupsResponse from(List<ExhibitionResult.RegionGroup> groups) {
			return new RegionGroupsResponse(groups.stream().map(RegionGroupResponse::from).toList());
		}
	}

	/** 지역 필터 그룹 1개 — 칩 1개. 검색 시 regions를 콤마로 이어 region 파라미터에 그대로 넣는다. */
	public record RegionGroupResponse(
			@Schema(description = "그룹 코드", example = "GYEONGGI_INCHEON") String code,
			@Schema(description = "칩 라벨", example = "경기·인천") String label,
			@Schema(description = "그룹에 속한 지역 코드(목록 검색 region 파라미터 값)",
					example = "[\"GYEONGGI\",\"INCHEON\"]") List<String> regions) {

		public static RegionGroupResponse from(ExhibitionResult.RegionGroup result) {
			return new RegionGroupResponse(result.code(), result.label(), result.regions());
		}
	}

	/** 개인 전시 등록 결과(3.3.3). */
	public record CreatedResponse(
			@Schema(description = "등록된 전시 ID", example = "108") Long exhibitionId,
			@Schema(description = "전시 출처. 개인 등록은 항상 CUSTOM.", example = "CUSTOM", allowableValues = "CUSTOM")
			String type) {

		public static CreatedResponse from(ExhibitionResult.Created result) {
			return new CreatedResponse(result.exhibitionId(), result.type());
		}
	}
}
