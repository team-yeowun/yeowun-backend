package modi.backend.application.exhibition.detail;

import modi.backend.application.exhibition.ExhibitionResult;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionGenre;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionType;
import modi.backend.domain.exhibition.catalog.ExhibitionViewCounter;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 전시 <b>상세</b> 유스케이스 — 조각(장소·상세·영업시간·작가·장르)을 두 애그리거트 루트에서 읽어 하나로 모은다.
 *
 * <p>사용자 상세는 <b>캐시에 담을 공용부({@link #assembleShared})</b>와 <b>요청자마다 달라지는 부분
 * ({@link #personalize})</b>으로 갈려 있다. 파사드가 그 사이에 캐시를 끼우기 때문이다. 조회수는
 * {@link ExhibitionViewCounter}에 누산되고 정본 반영은 배치 몫이라 이 경로에 DB 쓰기가 없다.
 * 기록 생성·AI 입력이 쓰는 {@link #getForSnapshot}은 조회수·개인화 없이 같은 조립만 한다 — 사용자 행동이 아니기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionDetailService {

	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;
	private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;
	private final RecordJpaRepository recordJpaRepository;
	private final ExhibitionViewCounter viewCounter;

	/**
	 * - 캐시에 담을 공용 상세 — 권한을 판정하고 개인화 없이 조각만 조립
	 *   - 없으면 404, 타인의 CUSTOM이면 403
	 *   - place·operatingHours·artists는 요청 시 조인해 조립
	 *
	 * - {@code cacheable}은 CATALOG일 때만 참이라 호출부는 그때만 캐시에 넣음
	 *   - 그러면 캐시 히트가 곧 "공개해도 되는 값"이라는 증명이 됨
	 *   - 히트 경로에서 권한을 다시 볼 필요가 없어짐
	 *   - 판정을 건너뛰는 경로가 애초에 만들어지지 않는다는 것이 요점
	 *
	 * - 서빙 경로는 외부 API를 부르지 않음
	 *   - 예전엔 CATALOG 최초 진입 시 상세를 1회 지연 수집했으나 제거(2026-07-21)
	 *   - 트랜잭션 안에서 외부 HTTP를 쳐 응답 시간이 원천에 묶였음
	 *   - 상세 충전은 수집 파이프라인(상세 스텝 — DRAFT_STAGED 소비)의 몫
	 */
	@Transactional(readOnly = true)
	public ExhibitionResult.SharedDetail assembleShared(Long exhibitionId, Long requesterId) {
		Exhibition exhibition = exhibitionRepository.findById(exhibitionId)
				.orElseThrow(() -> new CoreException(ExhibitionErrorCode.EXHIBITION_NOT_FOUND));
		if (!exhibition.isAccessibleBy(requesterId)) {
			throw new CoreException(ErrorType.FORBIDDEN, "타인의 개인 전시 접근: " + exhibitionId);
		}
		return new ExhibitionResult.SharedDetail(assembleDetail(exhibition, false, false),
				exhibition.getType() == ExhibitionType.CATALOG);
	}

	/**
	 * - 공용 상세에 요청자 기준 개인화와 누산분을 얹어 응답을 완성
	 *   - 관심·기록 여부는 요청자마다 다르므로 캐시 밖에서 읽음
	 *   - 비로그인이면 둘 다 false이고 조회도 하지 않음
	 *
	 * - 조회수는 누산기에만 올림
	 *   - 정본 반영은 배치 몫이라 이 경로에 DB 쓰기가 없음
	 *   - 응답에는 정본 + 아직 반영되지 않은 누산분을 합쳐 내보냄
	 */
	@Transactional(readOnly = true)
	public ExhibitionResult.Detail personalize(ExhibitionResult.Detail shared, Long requesterId) {
		long pending = viewCounter.increase(shared.exhibitionId());
		boolean bookmarked = requesterId != null
				&& exhibitionBookmarkRepository.existsActive(requesterId, shared.exhibitionId());
		boolean recorded = requesterId != null
				&& recordJpaRepository.existsByUserIdAndExhibitionIdAndDeletedAtIsNull(requesterId,
						shared.exhibitionId());
		return shared.withPersonalization(bookmarked, recorded).withViewCount(shared.viewCount() + pending);
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
		// 조각이 없을 때 센티넬을 넘긴다 — 결측 판단은 도메인이 하고, 조립부는 값을 그냥 읽는다.
		ExhibitionPlace place = exhibitionPlaceRepository.findById(exhibition.getExhibitionPlaceId())
				.orElseGet(ExhibitionPlace::unknown);
		ExhibitionDetail detail = exhibitionRepository.findDetail(exhibition.getId())
				.orElseGet(ExhibitionDetail::empty);
		PlaceHours placeHours = exhibitionPlaceRepository.findHours(exhibition.getExhibitionPlaceId())
				.orElseGet(PlaceHours::empty);

		List<String> artistNames = exhibitionRepository.findArtistNames(exhibition.getId());

		return ExhibitionResult.Detail.from(exhibition, place, detail, placeHours, artistNames,
				genreOf(exhibition.getId()), bookmarked, recorded);
	}

	private ExhibitionGenre genreOf(Long exhibitionId) {
		return exhibitionRepository.findGenre(exhibitionId).orElse(null);
	}
}
