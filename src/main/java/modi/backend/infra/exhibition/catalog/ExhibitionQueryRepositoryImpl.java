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

	/**
	 * 키셋 한 페이지 — <b>count 없이</b> 목록만 가져온다.
	 *
	 * <p>예전엔 {@code findAll(spec, Pageable)}을 쓰고 {@code getContent()}만 꺼냈는데, 그 메서드는 {@code Page}를
	 * 만들며 <b>총 건수 count를 함께 실행</b>한다(우리는 그 값을 버린다). Spring Data가 "첫 페이지이고 결과가
	 * 페이지 크기보다 적으면" 생략해 주기 때문에 데이터가 적을 땐 보이지 않다가, <b>페이지가 꽉 차는 순간</b>
	 * — 즉 다음 페이지가 있는 실사용에서 — 매 요청 count가 두 번 나갔다(하나는 여기, 하나는 totalCount용).
	 *
	 * <p>슬라이스에는 총 건수가 필요 없으므로 fluent query로 정렬·상한만 걸어 목록을 받는다.
	 */
	@Override
	public List<Exhibition> searchSlice(ExhibitionQuery query, int limitPlusOne) {
		return jpaRepository.findBy(ExhibitionSpecifications.slice(query),
				fluent -> fluent.sortBy(sortFor(query.sort())).limit(Math.max(1, limitPlusOne)).all());
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
