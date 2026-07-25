package modi.backend.infra.exhibition.catalog;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionArtist;
import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
import modi.backend.domain.exhibition.catalog.ExhibitionGenre;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * 전시 애그리거트 어댑터(DIP) — 루트와 부속(상세 satellite·장르 정준·작가 조인)의 JpaRepository 4개를
 * 이 안에서 조율한다. 부속 JpaRepository는 이 패키지의 구현 세부다: application이 직접 주입하지 않는다.
 * upsert 분기(없으면 create, 있으면 entity 행위 메서드)는 전부 여기로 모여 반쪽 저장 경로가 사라진다.
 * 조회는 살아있는 행만 본다.
 */
@Repository
@RequiredArgsConstructor
public class ExhibitionRepositoryImpl implements ExhibitionRepository {

	private final ExhibitionJpaRepository jpaRepository;
	private final ExhibitionDetailJpaRepository detailJpaRepository;
	private final ExhibitionGenreJpaRepository genreJpaRepository;
	private final ExhibitionArtistJpaRepository artistLinkJpaRepository;

	@Override
	public Exhibition save(Exhibition exhibition) {
		return jpaRepository.save(exhibition);
	}

	@Override
	public Optional<Exhibition> findById(Long id) {
		return jpaRepository.findById(id).filter(exhibition -> exhibition.getDeletedAt() == null);
	}

	@Override
	public Optional<Exhibition> findByExternalId(String externalId) {
		return jpaRepository.findByExternalIdAndDeletedAtIsNull(externalId);
	}

	@Override
	public List<Exhibition> findAllActiveByIds(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return jpaRepository.findAllById(ids).stream()
				.filter(exhibition -> exhibition.getDeletedAt() == null)
				.toList();
	}

	// ── 상세 satellite(1:1) ─────────────────────────────────────────────────────

	@Override
	public void applyDetail(Long exhibitionId, String price, String description, String imgUrl, LocalDateTime now) {
		detailJpaRepository.findByExhibitionId(exhibitionId)
				.ifPresentOrElse(row -> {
					row.update(price, description, imgUrl, now);
					detailJpaRepository.save(row);
				}, () -> detailJpaRepository.save(
						ExhibitionDetail.create(exhibitionId, price, description, imgUrl, now)));
	}

	@Override
	public void markDetailChecked(Long exhibitionId, LocalDateTime now) {
		if (!detailJpaRepository.existsByExhibitionId(exhibitionId)) {
			detailJpaRepository.save(ExhibitionDetail.markChecked(exhibitionId, now));
		}
	}

	@Override
	public boolean hasDetail(Long exhibitionId) {
		return detailJpaRepository.existsByExhibitionId(exhibitionId);
	}

	@Override
	public Optional<ExhibitionDetail> findDetail(Long exhibitionId) {
		return detailJpaRepository.findByExhibitionId(exhibitionId);
	}

	@Override
	public List<ExhibitionDetail> findDetails(Collection<Long> exhibitionIds) {
		if (exhibitionIds == null || exhibitionIds.isEmpty()) {
			return List.of();
		}
		return detailJpaRepository.findByExhibitionIdIn(exhibitionIds);
	}

	@Override
	public List<ExhibitionDetail> findDetailsWithDescription() {
		return detailJpaRepository.findByDescriptionIsNotNull();
	}

	@Override
	public ExhibitionDetail saveDetail(ExhibitionDetail detail) {
		return detailJpaRepository.save(detail);
	}

	// ── 장르 정준층 ─────────────────────────────────────────────────────────────

	@Override
	public void applyGenre(Long exhibitionId, GenreResult result, LocalDateTime now) {
		genreJpaRepository.findByExhibitionId(exhibitionId)
				.ifPresentOrElse(existing -> {
					existing.reclassify(result.genreKeyword(), result.provider(), result.model(), now);
					genreJpaRepository.save(existing);
				}, () -> genreJpaRepository.save(ExhibitionGenre.create(exhibitionId, result.genreKeyword(),
						result.provider(), result.model(), now)));
	}

	@Override
	public Optional<ExhibitionGenre> findGenre(Long exhibitionId) {
		return genreJpaRepository.findByExhibitionId(exhibitionId);
	}

	// ── 작가 조인(N:M) ──────────────────────────────────────────────────────────

	@Override
	public void linkArtist(Long exhibitionId, Long artistId) {
		if (!artistLinkJpaRepository.existsByExhibitionIdAndArtistId(exhibitionId, artistId)) {
			artistLinkJpaRepository.save(ExhibitionArtist.of(exhibitionId, artistId));
		}
	}

	@Override
	public List<String> findArtistNames(Long exhibitionId) {
		return artistLinkJpaRepository.findArtistNames(exhibitionId);
	}
}
