package modi.backend.application.exhibition;

import java.time.LocalDate;
import java.util.List;

import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
import modi.backend.domain.exhibition.catalog.ExhibitionFormat;
import modi.backend.domain.exhibition.catalog.ExhibitionGenre;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRegionGroup;
import modi.backend.domain.exhibition.hours.PlaceHours;

/**
 * 전시 유스케이스 출력 모음. (Facade는 Result까지만)
 * 장소(name/region/주소/gps)는 {@link ExhibitionPlace}(N:1), 상세(price/description/img)는 {@link ExhibitionDetail}(1:1),
 * 영업시간은 {@link PlaceHours}, 작가는 조인에서 조립해 서비스가 넘긴다 — 응답 필드명·타입은 이관 전과 동일하다(API 계약 불변).
 *
 * <p><b>결측은 호출부가 아니라 도메인이 흡수한다</b>: 조각이 없으면 서비스가 {@code unknown()}·{@code empty()}
 * 센티넬을 넘기므로 여기서는 값을 그냥 읽는다. 결측 필드가 null로 내려가는 응답 계약은 그대로다.
 */
public final class ExhibitionResult {

	private ExhibitionResult() {
	}

	/**
	 * 목록 한 페이지 결과 — 커서 페이지네이션 shape(content·nextCursor·hasNext·totalCount).
	 *
	 * <p>총 건수를 응답에 <b>함께 담는다</b> — 기존 응답 계약 유지가 우선이라는 결정이다(프론트 1명, 호출
	 * 구조 변경 비용 회피). 목록이 count 지연에 묶이는 대가는 count 자체를 싸게 만드는 것(커버링 인덱스·
	 * 다중지역 힌트)으로 갚았다. 목록 없이 숫자만 필요한 화면(필터 시트)은 {@link Count}를 쓸 수 있다.
	 */
	public record ListPage(List<ListItem> content, String nextCursor, boolean hasNext, long totalCount) {
	}

	/**
	 * 필터 조건의 총 건수({@code GET /exhibitions/count}) — 목록 없이 숫자만 필요한 화면용 <b>보조</b> 엔드포인트.
	 * 목록 응답의 totalCount와 같은 조립 경로를 타므로 두 숫자는 어긋나지 않는다.
	 *
	 * <p>{@code exact}는 현재 <b>항상 true</b>다(정확한 count). 지금 넣어 두는 이유는 나중에 상한 근사로
	 * 갈아탈 때 필드를 그때 추가하면 프론트 재작업이 되기 때문이다 — 지금은 프론트가 안 읽으면 그만이라 비용이 0이고,
	 * 갈아타기가 백엔드 한 줄로 끝난다.
	 */
	public record Count(long count, boolean exact) {
	}

	/** 지역 필터 그룹(디자인 병합 칩) — code=그룹 식별자, regions=검색 region 파라미터로 펼칠 코드들. */
	public record RegionGroup(String code, String label, List<String> regions) {

		public static RegionGroup from(ExhibitionRegionGroup group) {
			return new RegionGroup(group.name(), group.label(),
				group.regions().stream().map(Enum::name).toList());
		}
	}

	/**
	 * 목록 항목(5.2 content[]). place 이름은 {@link ExhibitionPlace} 조인에서, <b>region·free는 전시 행의
	 * 복제본</b>에서 온다(V49). bookmarked는 Facade가 배치 조회해 주입.
	 *
	 * <p><b>왜 region을 전시장이 아니라 전시에서 읽는가</b>: 필터가 {@code exhibitions.region}을 보는데
	 * 표시는 전시장을 조인하면, 전시장 지역이 바뀐 순간 "서울로 검색했는데 경기라고 적힌 카드"가 나온다.
	 * 복제본은 갱신하지 않는 스냅샷이라 두 경로가 같은 값을 봐야 한다.
	 */
	public record ListItem(Long exhibitionId, String type, String title, String posterUrl,
			LocalDate startDate, LocalDate endDate, String place, String region, String category,
			String artistSummary, Integer dDay, boolean free, boolean bookmarked) {

		public static ListItem from(Exhibition exhibition, ExhibitionPlace place, LocalDate today,
				boolean bookmarked) {
			return new ListItem(
					// 코어(exhibitions)
					exhibition.getId(), exhibition.getType().name(), exhibition.getTitle(),
					exhibition.getPosterUrl(), exhibition.getStartDate(), exhibition.getEndDate(),

					// 전시장 이름은 조인 · 지역은 전시 행의 스냅샷(필터와 같은 출처)
					place.getName(), name(exhibition.getRegion()), name(exhibition.getCategory()),

					// 파생값(목록은 CATALOG만이라 artistSummary는 원천 미보유 → null)
					null, exhibition.dDay(today), exhibition.isFree(), bookmarked);
		}
	}

	/**
	 * 전시 상세(5.3). place·주소·gps·operatingHours는 조인(장소·영업시간)에서, price·description·imgUrl은 상세 satellite에서,
	 * artists·artistSummary는 작가 조인에서 조립한다. keywords는 정준층(장르). bookmarked·recorded는 요청자 기준(비로그인 false).
	 */
	public record Detail(Long exhibitionId, String type, String title, String posterUrl,
			LocalDate startDate, LocalDate endDate, String place, String region, String category, String format,
			String description, String operatingHours, String price, List<String> artists, List<String> keywords,
			String serviceName, String detailUrl, Double gpsX, Double gpsY,
			String address, String imgUrl, String phone, long viewCount, String sigungu, String placeUrl,
			String artistSummary, boolean free, boolean bookmarked, boolean recorded) {

		public static Detail from(Exhibition exhibition, ExhibitionPlace place, ExhibitionDetail detail,
				PlaceHours placeHours, List<String> artistNames, ExhibitionGenre genre, boolean bookmarked,
				boolean recorded) {
			List<String> artists = List.copyOf(artistNames);
			String artistSummary = artists.isEmpty() ? null : String.join(", ", artists);
			String price = detail.getPrice();

			// 필드가 29개라 어느 조각에서 왔는지가 잘 안 보인다 — 출처별로 줄을 끊어 둔다.
			return new Detail(
					// 코어(exhibitions)
					exhibition.getId(), exhibition.getType().name(), exhibition.getTitle(),
					exhibition.getPosterUrl(), exhibition.getStartDate(), exhibition.getEndDate(),

					// 전시장 이름은 조인 · 지역은 전시 행의 스냅샷(목록·필터와 같은 출처, V49)
					place.getName(), name(exhibition.getRegion()),
					name(exhibition.getCategory()), name(exhibition.getFormat()),

					// 상세(exhibition_detail) · 영업시간(place_hours)
					detail.getDescription(), placeHours.getFormatted(), price,

					// 작가 조인 · 장르 정준층
					artists, ExhibitionGenre.keywordsOf(genre),

					// 코어 원문 링크
					exhibition.getServiceName(), exhibition.getDetailUrl(),

					// 전시장 위치 · 상세 이미지 · 전시장 연락처 · 코어 조회수 · 전시장 주소보조
					place.getGpsX(), place.getGpsY(), place.getAddress(),
					detail.getImgUrl(),
					place.getPhone(),
					exhibition.getOurViewCount(),
					place.getSigungu(), place.getPlaceUrl(),

					// 파생값 · 요청자 기준 개인화 (free는 목록·필터와 같은 출처 = 전시 행의 굳은 판정, V49)
					artistSummary, exhibition.isFree(), bookmarked, recorded);
		}

		/**
		 * 조회수만 갈아 끼운다. 정본은 배치가 6시간마다 반영하므로, 응답에는
		 * <b>정본 + 아직 반영되지 않은 누산분</b>을 합쳐 내보내야 사용자가 보는 수가 즉시 오른다.
		 */
		public Detail withViewCount(long value) {
			return new Detail(exhibitionId, type, title, posterUrl, startDate, endDate, place, region, category,
					format, description, operatingHours, price, artists, keywords, serviceName, detailUrl,
					gpsX, gpsY, address, imgUrl, phone, value, sigungu, placeUrl, artistSummary, free,
					bookmarked, recorded);
		}
	}

	/** 홈 배너 항목(E-10). 배너 이미지는 전시 포스터(posterUrl)를 사용한다. */
	public record Banner(Long exhibitionId, String title, String bannerImageUrl,
			LocalDate startDate, LocalDate endDate, String place) {

		public static Banner from(Exhibition exhibition, ExhibitionPlace place) {
			return new Banner(exhibition.getId(), exhibition.getTitle(), exhibition.getPosterUrl(),
					exhibition.getStartDate(), exhibition.getEndDate(), place.getName());
		}
	}

	/** 개인 전시 등록 결과(3.3.3). */
	public record Created(Long exhibitionId, String type) {

		public static Created from(Exhibition exhibition) {
			return new Created(exhibition.getId(), exhibition.getType().name());
		}
	}

	/**
	 * 조회수 반영 결과(배치). {@code exhibitions}는 갱신된 전시 수, {@code views}는 반영한 조회 횟수 합계다.
	 * 둘 다 0이면 그 창에 조회가 없었거나 다른 인스턴스가 이미 가져갔다는 뜻이다.
	 */
	public record ViewCountFlush(int exhibitions, long views) {
	}

	private static String name(ExhibitionRegion region) {
		return region == null ? null : region.name();
	}

	private static String name(ExhibitionCategory category) {
		return category == null ? null : category.name();
	}

	private static String name(ExhibitionFormat format) {
		return format == null ? null : format.name();
	}

    /**
     * 홈 배너 목록(E-10)을 통째로 담는 그릇. 캐시가 {@code Class<T>}로 값을 꺼내는데
     * {@code List<Banner>}는 제네릭이라 그 타입으로 표현되지 않아, 한 겹 감싼다.
     * 감싼 김에 "캐시 값은 불변 record만"이라는 규율도 함께 지켜진다.
     */
    public record Banners(List<Banner> items) {
    }
}
