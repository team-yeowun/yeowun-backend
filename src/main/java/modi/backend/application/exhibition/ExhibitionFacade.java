package modi.backend.application.exhibition;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Artist;
import modi.backend.domain.exhibition.catalog.ArtistRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionFormat;
import modi.backend.domain.exhibition.catalog.ExhibitionGenre;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRegionGroup;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionSection;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.domain.venue.Venue;
import modi.backend.domain.venue.VenueErrorCode;
import modi.backend.domain.venue.VenueRepository;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;
import modi.backend.support.response.Cursor;
import modi.backend.support.time.AppTime;

/**
 * 전시 사용자 유스케이스 조율(03_전시.md) — 목록/탐색·배너·상세·개인 전시 등록/삭제. load·조율·save만 하고,
 * 상태 변경·규칙 판단은 도메인 엔티티에 위임한다. 수집·보강 파이프라인은 ingestion 슬라이스({@code ExhibitionIngestionOrchestrator})가
 * 따로 담당한다(조회 API와 배치의 결합 해소).
 *
 * <p>장소는 {@link ExhibitionPlace}(N:1, resolve-or-create), 상세는 satellite(1:1), 작가는 조인(N:M) —
 * 응답 조립 시 애그리거트 루트 포트에서 읽어 모은다(API 계약 불변).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionFacade {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionFacade.class);

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;
	private static final int ENDING_SOON_DAYS = 7;
	private static final int BANNER_LIMIT = 3;

	/** 전시 애그리거트 루트 — 코어 쓰기와 부속(상세·장르·작가 조인)의 단일 진입점. */
	private final ExhibitionRepository exhibitionRepository;
	/** 서빙 목록/탐색 전용(쓰기=루트, 읽기=쿼리 분리 — Specification·키셋 유지). */
	private final ExhibitionQueryRepository exhibitionQueryRepository;
	/** 전시장 애그리거트 루트(N:1 공유) — resolve-or-create와 영업시간 정준행(1:1)의 단일 진입점(ADR-05·06·07). */
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;
	/** 작가(정규화 이름 UK, 독립 애그리거트) — CUSTOM 등록의 artist 문자열을 resolve-or-create해 조인으로 잇는다. */
	private final ArtistRepository artistRepository;
	/** 상세 지연 수집(최초 상세 진입 1회)용 — 배치 동기화가 아니라 사용자 경로의 캐시 채움이다. */
	private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;
	private final VenueRepository venueRepository;
	private final RecordJpaRepository recordJpaRepository;
	private final GenreClassifier genreClassifier;

	/** 지역 필터 그룹 목록(디자인 병합 칩). 정적 enum 메타데이터라 조회 없이 변환만 한다. */
	public List<ExhibitionResult.RegionGroup> getRegionGroups() {
		return ExhibitionRegionGroup.all().stream().map(ExhibitionResult.RegionGroup::from).toList();
	}

	/**
	 * 목록/탐색(5.2). 필터 미지정 시 오늘 진행 중인 전시를 기본 노출한다. 비로그인은 CATALOG만.
	 * place·region은 전시장 조인에서, free는 상세 가격에서 조립한다.
	 */
	@Transactional(readOnly = true)
	public ExhibitionResult.ListPage search(ExhibitionCriteria.Search criteria) {
		LocalDate today = LocalDate.now(AppTime.KST);
		String keyword = normalizeKeyword(criteria.keyword());
		List<ExhibitionRegion> regions = parseRegions(criteria.region());
		List<ExhibitionCategory> categories = parseCategories(criteria.category());
		ExhibitionSection section = ExhibitionSection.from(criteria.section());
		String sort = canonicalSort(criteria.sort());
		int size = clampSize(criteria.size());
		LocalDate ongoingOn = resolveOngoingOn(criteria.date(), keyword, section, today);
		LocalDate notEndedOn = resolveNotEndedOn(ongoingOn, keyword, today);

		if ("distance".equals(sort)) {
			return searchByDistance(criteria, today, size,
					buildQuery(keyword, ongoingOn, notEndedOn, regions, categories, section, today,
							criteria.period(), "distance", null, null, criteria.requesterId()));
		}

		Cursor cursor = Cursor.decode(criteria.cursor(), sort).orElse(null);
		String cursorKey = cursor == null ? null : cursor.key();
		Long cursorId = cursor == null ? null : cursor.lastId();
		ExhibitionQuery query = buildQuery(keyword, ongoingOn, notEndedOn, regions, categories, section, today,
				criteria.period(), sort, cursorKey, cursorId, criteria.requesterId());

		List<Exhibition> rows = exhibitionQueryRepository.searchSlice(query, size + 1);
		boolean hasNext = rows.size() > size;
		List<Exhibition> page = hasNext ? rows.subList(0, size) : rows;

		List<ExhibitionResult.ListItem> content = toListItems(page, today, criteria.requesterId());
		String nextCursor = hasNext ? encodeCursor(sort, page.get(page.size() - 1)) : null;
		long totalCount = exhibitionQueryRepository.count(query);
		return new ExhibitionResult.ListPage(content, nextCursor, hasNext, totalCount);
	}

	/** 페이지 전시들을 장소·상세·관심 배치 조회로 조립한 목록 항목으로 변환한다(N+1 방지). */
	private List<ExhibitionResult.ListItem> toListItems(List<Exhibition> page, LocalDate today, Long requesterId) {
		if (page.isEmpty()) {
			return List.of();
		}
		Map<Long, ExhibitionPlace> placesById = placesByIdFor(page);
		// 목록이 상세에서 쓰는 값은 가격 하나뿐이라 가격만 읽는다(description을 실어오지 않는다).
		Map<Long, String> pricesById = exhibitionRepository
				.findPricesByExhibitionIds(page.stream().map(Exhibition::getId).toList());
		Set<Long> bookmarked = bookmarkedIds(requesterId, page);
		return page.stream().map(e -> ExhibitionResult.ListItem.from(e, placesById.get(e.getExhibitionPlaceId()),
				today, Exhibition.isFree(pricesById.get(e.getId())), bookmarked.contains(e.getId()))).toList();
	}

	private Map<Long, ExhibitionPlace> placesByIdFor(List<Exhibition> exhibitions) {
		Set<Long> placeIds = exhibitions.stream().map(Exhibition::getExhibitionPlaceId).collect(Collectors.toSet());
		return exhibitionPlaceRepository.findAllByIds(placeIds).stream()
				.collect(Collectors.toMap(ExhibitionPlace::getId, p -> p, (a, b) -> a));
	}

	/**
	 * 홈 배너(E-10). 오늘 진행 중인 전시 중 조회수 상위 최대 3개를 노출한다. 진행 중 전시가 없으면 빈 배열.
	 */
	@Transactional(readOnly = true)
	public List<ExhibitionResult.Banner> banners() {
		List<Exhibition> rows = exhibitionQueryRepository.findOngoingCatalogTopByViews(LocalDate.now(AppTime.KST),
				BANNER_LIMIT);
		Map<Long, ExhibitionPlace> placesById = placesByIdFor(rows);
		return rows.stream()
				.map(e -> ExhibitionResult.Banner.from(e, placesById.get(e.getExhibitionPlaceId())))
				.toList();
	}

	/**
	 * 거리순(5.2, P2 best-effort). lat·lng 필수. 좌표는 전시장(exhibition_place)에서 온다 — 후보의 장소를 배치 로드해
	 * 단순 제곱거리로 정렬(좌표 null은 뒤로)하고, 커서는 마지막 id 위치 기준으로 슬라이스한다.
	 */
	private ExhibitionResult.ListPage searchByDistance(ExhibitionCriteria.Search criteria, LocalDate today,
			int size, ExhibitionQuery query) {
		if (criteria.lat() == null || criteria.lng() == null) {
			throw new CoreException(ErrorType.INVALID_INPUT, "거리순 정렬은 lat·lng가 필요합니다.");
		}
		double lat = criteria.lat();
		double lng = criteria.lng();
		List<Exhibition> candidates = exhibitionQueryRepository.searchAll(query);
		Map<Long, ExhibitionPlace> placesById = placesByIdFor(candidates);
		List<Exhibition> ordered = candidates.stream()
				.sorted(distanceComparator(placesById, lat, lng))
				.toList();

		Cursor cursor = Cursor.decode(criteria.cursor(), "distance").orElse(null);
		int start = cursor == null ? 0 : nextIndexAfter(ordered, cursor.lastId());
		int end = Math.min(start + size, ordered.size());
		List<Exhibition> page = start >= ordered.size() ? List.of() : ordered.subList(start, end);
		boolean hasNext = end < ordered.size();

		List<ExhibitionResult.ListItem> content = toListItems(page, today, criteria.requesterId());
		String nextCursor = null;
		if (hasNext) {
			Exhibition last = page.get(page.size() - 1);
			nextCursor = Cursor.of("distance",
					String.valueOf(distanceSq(placesById.get(last.getExhibitionPlaceId()), lat, lng)), last.getId())
					.encode();
		}
		return new ExhibitionResult.ListPage(content, nextCursor, hasNext, ordered.size());
	}

	/**
	 * 상세(5.3). 없으면 404, 타인의 CUSTOM이면 403. place·operatingHours·artists는 요청 시 조인해 조립한다.
	 *
	 * <p><b>서빙 경로는 외부 API를 부르지 않는다</b>: 예전엔 CATALOG 최초 진입 시 상세를 1회 지연 수집했으나
	 * 제거했다(2026-07-21) — ① 트랜잭션 안에서 외부 HTTP를 쳐 응답 시간이 원천에 묶였고, ② 승격이 항상 상세를
	 * 함께 쓰는 지금(ADR-12) {@code hasDetail()==false}인 CATALOG 전시는 draft 도입 이전 행에만 남는다.
	 * 상세 충전은 수집 파이프라인(상세 스텝 — DRAFT_STAGED 소비)의 몫이다.
	 */
	@Transactional
	public ExhibitionResult.Detail getDetail(ExhibitionCriteria.Detail criteria) {
		Exhibition exhibition = exhibitionRepository.findById(criteria.exhibitionId())
				.orElseThrow(() -> new CoreException(ExhibitionErrorCode.EXHIBITION_NOT_FOUND));
		if (!exhibition.isAccessibleBy(criteria.requesterId())) {
			throw new CoreException(ErrorType.FORBIDDEN, "타인의 개인 전시 접근: " + criteria.exhibitionId());
		}
		exhibition.increaseView();
		exhibitionRepository.save(exhibition);
		Long requesterId = criteria.requesterId();
		boolean bookmarked = requesterId != null
				&& exhibitionBookmarkRepository.existsActive(requesterId, exhibition.getId());
		boolean recorded = requesterId != null
				&& recordJpaRepository.existsByUserIdAndExhibitionIdAndDeletedAtIsNull(requesterId, exhibition.getId());
		return assembleDetail(exhibition, bookmarked, recorded);
	}

	/** 스냅샷/조회용 — 조회수 증가·외부 상세수집·개인화 없이 DB에서만 전시를 읽어 반환한다(기록 생성 등 내부 사용). */
	@Transactional(readOnly = true)
	public ExhibitionResult.Detail getForSnapshot(Long exhibitionId, Long requesterId) {
		Exhibition e = exhibitionRepository.findById(exhibitionId)
				.orElseThrow(() -> new CoreException(ExhibitionErrorCode.EXHIBITION_NOT_FOUND));
		if (!e.isAccessibleBy(requesterId)) {
			throw new CoreException(ErrorType.FORBIDDEN, "타인의 개인 전시 접근: " + exhibitionId);
		}
		return assembleDetail(e, false, false);
	}

	/** 상세 응답 조립 — 장소·상세·영업시간·작가·장르를 두 애그리거트 루트에서 읽어 하나로 모은다. */
	private ExhibitionResult.Detail assembleDetail(Exhibition exhibition, boolean bookmarked, boolean recorded) {
		ExhibitionPlace place = exhibitionPlaceRepository.findById(exhibition.getExhibitionPlaceId()).orElse(null);
		ExhibitionDetail detail = exhibitionRepository.findDetail(exhibition.getId()).orElse(null);
		PlaceHours placeHours = place == null ? null
				: exhibitionPlaceRepository.findHours(place.getId()).orElse(null);
		List<String> artistNames = exhibitionRepository.findArtistNames(exhibition.getId());
		return ExhibitionResult.Detail.from(exhibition, place, detail, placeHours, artistNames,
				genreOf(exhibition.getId()), bookmarked, recorded);
	}

	private ExhibitionGenre genreOf(Long exhibitionId) {
		return exhibitionRepository.findGenre(exhibitionId).orElse(null);
	}

	/**
	 * 개인 전시 등록(5.4). 제목 필수·기간·개인전 작가 검증은 Entity에서. 전시장은 resolve-or-create(정규화 이름)로 확정해
	 * {@code exhibition_place_id NOT NULL}을 지탱하고, 작가 문자열은 resolve-or-create + 조인으로 잇는다.
	 */
	@Transactional
	public ExhibitionResult.Created registerCustom(ExhibitionCriteria.CustomCreate criteria) {
		ExhibitionRegion region = criteria.region() == null ? null : ExhibitionRegion.from(criteria.region());
		ExhibitionCategory category = criteria.category() == null ? null
				: ExhibitionCategory.from(criteria.category());
		ExhibitionFormat format = criteria.format() == null ? null : ExhibitionFormat.from(criteria.format());
		String placeName = criteria.place();
		if (criteria.venueId() != null) {
			Venue venue = venueRepository.findById(criteria.venueId())
					.orElseThrow(() -> new CoreException(VenueErrorCode.VENUE_NOT_FOUND));
			placeName = venue.getName();
			if (region == null) {
				region = venue.getRegion();
			}
		}
		// place·venueId 모두 없으면 장소 미상 센티넬로 수렴한다(exhibition_place_id NOT NULL 지탱) — 제목만 등록도 그대로 동작한다.
		ExhibitionPlace place = exhibitionPlaceRepository.resolveOrCreate(placeName, region, null, null, null);
		// 장르: 사용자가 직접 고르면 그 값(provider=USER), 미지정이면 분류기(AI 체인/mock)가 부여한다.
		// 분류기는 이제 실패 시 예외를 던지지만(ADR-11 계약 반전), 등록은 장르(부가 기능) 때문에 깨지지 않는다 —
		// 전 공급자 장애면 장르 없이 등록한다(기능 강등 — 미지정 CUSTOM + 전 AI 동시 장애의 교집합이라 드물다).
		GenreResult genre = null;
		if (criteria.genreKeyword() != null && !criteria.genreKeyword().isBlank()) {
			if (!GenreKeyword.contains(criteria.genreKeyword())) {
				throw new CoreException(ErrorType.INVALID_INPUT, "정의되지 않은 장르 키워드: " + criteria.genreKeyword());
			}
			genre = GenreResult.user(criteria.genreKeyword());
		} else {
			try {
				genre = genreClassifier.classify(genreRequest(
						new GenreClassification(criteria.title(), category == null ? null : category.name(),
								null, placeName, criteria.artist(), null)));
			} catch (GenreClassificationException e) {
				log.warn("CUSTOM 등록 장르 분류 실패 — 장르 없이 등록(기능 강등): {}", e.getMessage());
			}
		}
		Exhibition exhibition = Exhibition.createCustom(criteria.ownerId(), criteria.title(), place.getId(),
				criteria.startDate(), criteria.endDate(), category, format, criteria.artist(), criteria.posterUrl());
		Exhibition saved = exhibitionRepository.save(exhibition);
		linkArtist(saved.getId(), criteria.artist());
		if (genre != null) {
			exhibitionRepository.applyGenre(saved.getId(), genre, LocalDateTime.now());
		}
		return ExhibitionResult.Created.from(saved);
	}

	/** 작가 문자열을 resolve-or-create(정규화 이름 UK)해 전시와 조인(멱등)한다. 이름이 비면 건너뛴다. */
	private void linkArtist(Long exhibitionId, String rawArtist) {
		String normalized = Artist.normalize(rawArtist);
		if (normalized == null) {
			return;
		}
		Artist artist = artistRepository.findByName(normalized)
				.orElseGet(() -> artistRepository.save(Artist.create(normalized)));
		exhibitionRepository.linkArtist(exhibitionId, artist.getId());
	}

	/**
	 * 개인 전시(CUSTOM) 동반 삭제 — 본인이 등록한 CUSTOM만 soft-delete(공용 CATALOG·타인 전시·이미 삭제된 전시는 무시), 멱등.
	 */
	@Transactional
	public void deleteCustomOwnedBy(Long exhibitionId, Long ownerId) {
		exhibitionRepository.findById(exhibitionId)
				.filter(exhibition -> exhibition.isCustomOwnedBy(ownerId))
				.ifPresent(exhibition -> {
					exhibition.delete();
					exhibitionRepository.save(exhibition);
				});
	}

	/**
	 * 이 조회에 "진행 중" 기간 조건을 걸지 결정한다(null이면 기간 무관).
	 *
	 * <p>기준은 <b>사용자가 무엇을 기대하는가</b>다 — 목록을 좁히는 필터(지역·카테고리)는 기본 목록과 같은 기간을
	 * 유지하고, 스스로 기간 의미를 갖는 섹션과 "아는 것을 찾는" 검색만 예외로 둔다.
	 */
	private LocalDate resolveOngoingOn(LocalDate date, String keyword, ExhibitionSection section, LocalDate today) {
		if (date != null) {
			return date;
		}
		if (section != null) {
			// 섹션은 스스로 기간의 의미를 갖는다 — "지금 볼 수 있는"(곧 끝남·무료)이면 진행 중,
			// "이번 달 새로 열리는"이면 아직 열리지 않은 전시를 보여주는 것이 목적이라 기간 조건을 걸지 않는다.
			return section.requiresOngoing() ? today : null;
		}
		if (keyword != null && !keyword.isBlank()) {
			// 검색은 "목록 좁히기"가 아니라 "아는 것 찾기"라 진행 중으로 묶지 않는다 —
			// 다음 달 개막 전시도 이름으로 찾을 수 있어야 한다. 대신 끝난 전시는 내보내지 않는다(notEndedOn).
			return null;
		}
		// 지역·카테고리는 목록을 좁히는 필터일 뿐이라 기본(필터 없음)과 같은 기간 조건을 유지한다 —
		// 필터를 걸었다고 다른 목록(미래·종료 전시)으로 바뀌면 안 된다.
		return today;
	}

	/**
	 * 검색에만 거는 "아직 끝나지 않음" 조건. 진행 중 조건({@code ongoingOn})이 이미 걸렸다면 불필요하다.
	 *
	 * <p>검색은 아직 열지 않은 전시를 이름으로 찾을 수 있어야 하지만, <b>이미 끝난 전시까지 보여줄 이유는 없다</b>.
	 * 그래서 시작일은 열어 두고 종료일만 막는다.
	 */
	private LocalDate resolveNotEndedOn(LocalDate ongoingOn, String keyword, LocalDate today) {
		if (ongoingOn != null || keyword == null || keyword.isBlank()) {
			return null;
		}
		return today;
	}

	private ExhibitionQuery buildQuery(String keyword, LocalDate ongoingOn, LocalDate notEndedOn,
			List<ExhibitionRegion> regions, List<ExhibitionCategory> categories, ExhibitionSection section,
			LocalDate today, String period, String sort, String cursorKey, Long cursorId, Long requesterId) {
		LocalDate from = null;
		LocalDate to = null;
		if (section == ExhibitionSection.ENDING_SOON) {
			from = today;
			to = today.plusDays(ENDING_SOON_DAYS);
		} else if (section == ExhibitionSection.OPENING_THIS_MONTH) {
			if ("week".equalsIgnoreCase(period == null ? "" : period.trim())) {
				from = today.with(DayOfWeek.MONDAY);
				to = today.with(DayOfWeek.SUNDAY);
			} else {
				from = today.withDayOfMonth(1);
				to = today.with(TemporalAdjusters.lastDayOfMonth());
			}
		}
		return new ExhibitionQuery(keyword, ongoingOn, notEndedOn, regions, categories, section, from, to, sort,
				cursorKey, cursorId, requesterId);
	}

	private Set<Long> bookmarkedIds(Long requesterId, List<Exhibition> page) {
		if (requesterId == null || page.isEmpty()) {
			return Set.of();
		}
		List<Long> ids = page.stream().map(Exhibition::getId).toList();
		return exhibitionBookmarkRepository.findBookmarkedExhibitionIds(requesterId, ids);
	}

	private static String encodeCursor(String sort, Exhibition last) {
		String key = switch (sort) {
			case "ending" -> last.getEndDate() == null ? null : last.getEndDate().toString();
			case "popular" -> String.valueOf(last.getOurViewCount());
			default -> last.getStartDate() == null ? null : last.getStartDate().toString();
		};
		return Cursor.of(sort, key, last.getId()).encode();
	}

	private static Comparator<Exhibition> distanceComparator(Map<Long, ExhibitionPlace> placesById, double lat,
			double lng) {
		return Comparator.comparingDouble((Exhibition e) -> distanceSq(placesById.get(e.getExhibitionPlaceId()), lat,
				lng)).thenComparing(Exhibition::getId);
	}

	/** 단순 제곱 유클리드 거리(좌표 null은 최댓값 → 뒤로). 좌표는 전시장에서 온다. */
	private static double distanceSq(ExhibitionPlace place, double lat, double lng) {
		if (place == null || place.getGpsX() == null || place.getGpsY() == null) {
			return Double.MAX_VALUE;
		}
		double dx = place.getGpsX() - lng;
		double dy = place.getGpsY() - lat;
		return dx * dx + dy * dy;
	}

	private static int nextIndexAfter(List<Exhibition> ordered, Long lastId) {
		for (int i = 0; i < ordered.size(); i++) {
			if (ordered.get(i).getId().equals(lastId)) {
				return i + 1;
			}
		}
		return 0;
	}

	private static String normalizeKeyword(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() < 2) {
			throw new CoreException(ErrorType.INVALID_INPUT, "검색어는 최소 2글자여야 합니다: " + raw);
		}
		return trimmed;
	}

	private static List<ExhibitionRegion> parseRegions(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.map(ExhibitionRegion::from).toList();
	}

	private static List<ExhibitionCategory> parseCategories(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.map(ExhibitionCategory::from).toList();
	}

	private static String canonicalSort(String sort) {
		if (sort == null) {
			return "latest";
		}
		return switch (sort.trim().toLowerCase()) {
			case "ending" -> "ending";
			case "popular" -> "popular";
			case "distance" -> "distance";
			default -> "latest";
		};
	}

	private static int clampSize(Integer size) {
		if (size == null || size < 1) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}

	/** 이 유스케이스가 분류기에게 무엇을 시킬지 — 표준 지시 + 마스터 전체 허용. 요청 조립은 서비스의 결정이다. */
	private GenreClassificationRequest genreRequest(GenreClassification subject) {
		return new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText());
	}

}
