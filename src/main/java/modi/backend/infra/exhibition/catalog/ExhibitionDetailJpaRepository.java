package modi.backend.infra.exhibition.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.domain.exhibition.catalog.ExhibitionDetail;

/** Spring Data JPA — 전시 상세 satellite. */
public interface ExhibitionDetailJpaRepository extends JpaRepository<ExhibitionDetail, Long> {

	Optional<ExhibitionDetail> findByExhibitionId(Long exhibitionId);

	boolean existsByExhibitionId(Long exhibitionId);

	List<ExhibitionDetail> findByExhibitionIdIn(Collection<Long> exhibitionIds);

	List<ExhibitionDetail> findByDescriptionIsNotNull();

	/**
	 * 가격만 뽑는 프로젝션(목록의 free 배지용). 상세 행에는 평균 1KB가 넘는 {@code description}이 있어
	 * 엔티티를 통째로 읽으면 페이지당 수십 KB를 읽고 버리게 된다 — 쓰는 컬럼만 읽는다.
	 */
	@Query("select d.exhibitionId, d.price from ExhibitionDetail d where d.exhibitionId in :exhibitionIds")
	List<Object[]> findPrices(@Param("exhibitionIds") Collection<Long> exhibitionIds);
}
