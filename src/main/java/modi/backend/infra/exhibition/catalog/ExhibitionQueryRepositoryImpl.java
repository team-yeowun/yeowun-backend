package modi.backend.infra.exhibition.catalog;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionSort;
import modi.backend.domain.exhibition.catalog.ExhibitionType;

/**
 * {@link ExhibitionQueryRepository} 어댑터 — 서빙 목록/탐색을 Specification + 키셋(커서) 페이지네이션으로 처리한다.
 * 정렬은 (정렬컬럼, id) 조합이며 nulls last로 결정적이다. 애그리거트 쓰기 경로와 분리돼 있어
 * 커버링 인덱스·키셋 최적화가 루트 로딩 방식 변화에 영향받지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class ExhibitionQueryRepositoryImpl implements ExhibitionQueryRepository {

	private final ExhibitionJpaRepository jpaRepository;

	@Override
	public List<Exhibition> searchSlice(ExhibitionQuery query, int limitPlusOne) {
		return jpaRepository.findAll(ExhibitionSpecifications.slice(query),
				PageRequest.of(0, limitPlusOne, sortFor(query.sort()))).getContent();
	}

	@Override
	public long count(ExhibitionQuery query) {
		return jpaRepository.count(ExhibitionSpecifications.filter(query));
	}

	@Override
	public List<Exhibition> searchAll(ExhibitionQuery query) {
		return jpaRepository.findAll(ExhibitionSpecifications.filter(query));
	}

	@Override
	public List<Exhibition> findOngoingCatalogTopByViews(LocalDate onDate, int limit) {
		return jpaRepository
				.findByTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNullOrderByOurViewCountDesc(
						ExhibitionType.CATALOG, onDate, onDate, PageRequest.of(0, Math.max(1, limit)));
	}

	/**
	 * 정렬 축 → (정렬컬럼, id) 결정적 정렬. 컬럼·방향은 {@link ExhibitionSort}가 들고 있어
	 * 키셋 경계(Specification)와 <b>같은 출처</b>를 본다 — 둘이 어긋나면 행이 누락·중복된다.
	 */
	private static Sort sortFor(ExhibitionSort sort) {
		ExhibitionSort resolved = sort == null || !sort.sortedByDatabase() ? ExhibitionSort.LATEST : sort;
		return resolved.ascending()
				? Sort.by(Sort.Order.asc(resolved.property()), Sort.Order.asc("id"))
				: Sort.by(Sort.Order.desc(resolved.property()), Sort.Order.desc("id"));
	}
}
