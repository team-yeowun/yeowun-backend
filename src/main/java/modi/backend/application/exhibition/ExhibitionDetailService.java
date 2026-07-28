package modi.backend.application.exhibition;

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
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 전시 <b>상세</b> 유스케이스 — 조각(장소·상세·영업시간·작가·장르)을 두 애그리거트 루트에서 읽어 하나로 모은다.
 *
 * <p>사용자 상세({@link #getDetail})는 조회수를 올리므로 읽기 전용이 아니다. 기록 생성·AI 입력이 쓰는
 * {@link #getForSnapshot}은 조회수·개인화 없이 같은 조립만 한다 — 사용자 행동이 아니기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionDetailService {

	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;
	private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;
	private final RecordJpaRepository recordJpaRepository;

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
}
