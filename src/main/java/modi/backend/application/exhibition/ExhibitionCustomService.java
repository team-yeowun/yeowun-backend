package modi.backend.application.exhibition;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Artist;
import modi.backend.domain.exhibition.catalog.ArtistRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionFormat;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.domain.venue.Venue;
import modi.backend.domain.venue.VenueErrorCode;
import modi.backend.domain.venue.VenueRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * <b>개인 전시(CUSTOM)</b> 등록·삭제 유스케이스. 조회 경로와 협력자가 갈린다 — 여기서만 작가·전시관·장르 분류기를 쓴다.
 *
 * <p>등록은 전시장 resolve-or-create로 {@code exhibition_place_id NOT NULL}을 지탱하고, 작가 문자열도
 * resolve-or-create + 조인으로 잇는다. 장르는 사용자가 고르면 그 값, 아니면 분류기가 부여한다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionCustomService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionCustomService.class);

	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;
	/** 작가(정규화 이름 UK, 독립 애그리거트) — CUSTOM 등록의 artist 문자열을 resolve-or-create해 조인으로 잇는다. */
	private final ArtistRepository artistRepository;
	private final VenueRepository venueRepository;
	private final GenreClassifier genreClassifier;

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

	/** 이 유스케이스가 분류기에게 무엇을 시킬지 — 표준 지시 + 마스터 전체 허용. 요청 조립은 서비스의 결정이다. */
	private GenreClassificationRequest genreRequest(GenreClassification subject) {
		return new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText());
	}
}
