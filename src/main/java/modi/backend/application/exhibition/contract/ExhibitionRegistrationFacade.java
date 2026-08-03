package modi.backend.application.exhibition.contract;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * {@link ExhibitionRegistrar} 구현 — 수집이 완성한 전시 스냅샷을 코어에 등록한다(구 draft 승격 본문, ADR-12).
 * [전시장 resolve → 전시 생성 → 상세 satellite → 장르 정준행]이 한 트랜잭션이고, 같은 원천이 이미 있으면
 * 새로 만들지 않고 그 전시로 응답한다({@code exhibitions.external_id} UK가 최후의 멱등 가드).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionRegistrationFacade implements ExhibitionRegistrar {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionRegistrationFacade.class);

	/** 전시 애그리거트 루트 — 코어 생성·부속(상세·장르) upsert의 단일 진입점. */
	private final ExhibitionRepository exhibitionRepository;
	/** 전시장 애그리거트 루트 — resolve-or-create·상세 보강의 단일 진입점. */
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Override
	@Transactional
	public Registered register(ExhibitionRegistration r, LocalDateTime now) {
		Exhibition existing = exhibitionRepository.findByExternalId(r.externalId()).orElse(null);
		if (existing != null) {
			return new Registered(existing.getId());
		}
		// resolve-or-create는 전시장 축(PLACE_STAGED)이 먼저 만들어두는 게 정상 경로지만, 축 병렬성 때문에
		// 승격이 먼저 닿는 경합의 안전망으로 유지한다(설계 §6-2 — 멱등이라 중복 생성이 없다).
		ExhibitionPlace place = exhibitionPlaceRepository.resolveOrCreate(r.placeName(), r.region(), r.sigungu(),
				r.gpsX(), r.gpsY());
		// 지역·무료는 여기가 "적재 시점"이다(V49). 지역은 방금 resolve한 전시장의 값을,
		// 무료는 수집이 완성해 온 가격을 도메인 규칙으로 판정해 전시 행에 굳힌다.
		// 계약(ExhibitionRegistration)은 그대로다 — 수집은 복제본도 판정 규칙도 몰라야 한다(ADR-12).
		Exhibition exhibition = Exhibition.createCatalog(r.externalId(), r.title(), place.getId(), place.getRegion(),
				r.startDate(), r.endDate(), r.category(), r.posterUrl(), r.detailUrl(), r.serviceName());
		Exhibition promoted = exhibitionRepository.save(exhibition);
		applyDetail(r, promoted, place, now);
		exhibitionRepository.applyGenre(promoted.getId(),
				new GenreResult(r.genreKeyword(), r.genreProvider(), r.genreModel()), now);
		log.info("전시 등록(externalId={} → exhibitionId={})", r.externalId(), promoted.getId());
		return new Registered(promoted.getId());
	}

	/** 상세분 이관 — 값이 있으면 satellite·전시장 보강, 무상세 확인이었으면 확인 완료 표식만(기존 의미 보존). */
	private void applyDetail(ExhibitionRegistration r, Exhibition promoted, ExhibitionPlace place, LocalDateTime now) {
		boolean hasDetailValues = r.price() != null || r.description() != null || r.imgUrl() != null;
		if (hasDetailValues) {
			exhibitionRepository.applyDetail(promoted.getId(), r.price(), r.description(), r.imgUrl(), now);
		} else {
			exhibitionRepository.markDetailChecked(promoted.getId(), now);
		}
		// 무료 판정을 전시 행에 굳힌다(V49). 가격은 상세(satellite)에 있지만 필터가 보는 값은 루트 컬럼이라,
		// 상세를 반영한 직후 같은 tx에서 다시 굳혀야 둘이 어긋나지 않는다. 규칙은 도메인이 안다.
		promoted.applyPriceJudgement(r.price());
		exhibitionRepository.save(promoted);
		if (r.placeAddr() != null || r.placePhone() != null || r.placeUrl() != null) {
			place.enrichDetail(r.placeAddr(), r.placePhone(), r.placeUrl());
			exhibitionPlaceRepository.save(place);
		}
	}
}
