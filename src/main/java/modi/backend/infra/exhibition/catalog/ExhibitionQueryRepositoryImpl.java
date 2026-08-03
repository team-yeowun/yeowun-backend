package modi.backend.infra.exhibition.catalog;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionSort;
import modi.backend.domain.exhibition.catalog.ExhibitionType;

/**
 * {@link ExhibitionQueryRepository} 어댑터 — 서빙 목록/탐색을 Specification + 키셋(커서) 페이지네이션으로 처리한다.
 * 정렬은 (정렬컬럼, id) 조합으로 결정적이다. 날짜 축은 정규화(V47) 이후 NULL이 없고 미상이 센티널로 적혀
 * 있어 예전의 nulls last 자리에 자연히 선다. 애그리거트 쓰기 경로와 분리돼 있어
 * 커버링 인덱스·키셋 최적화가 루트 로딩 방식 변화에 영향받지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class ExhibitionQueryRepositoryImpl implements ExhibitionQueryRepository {

	/**
	 * <b>지역이 정확히 1개일 때만</b> 목록이 타야 하는 인덱스(V51) — 정렬 축별로 하나씩.
	 * 슬라이스 SQL에 {@code USE INDEX (…)}로 실린다. 그 밖의 경우엔 힌트를 아예 붙이지 않는다.
	 *
	 * <p><b>왜 지역에만 힌트가 필요한가</b>: V49가 region을 이 테이블로 비정규화한 뒤 지역 술어는
	 * 정렬 인덱스 스캔의 <b>잔여 필터</b>가 됐다. 그러면 정렬 순서대로 걸어가며 21건이 찰 때까지
	 * 계속 걷고, 비용은 지역 크기가 아니라 <b>"오늘로부터 그 지역의 최근 진행 전시까지의 날짜 간격"</b>에
	 * 비례한다(진행 중이 0건이면 상한이 없다). 1M 실측 최신순: 서울 0.3ms인데 제주 657ms,
	 * 세종 2,726ms, 충남 17,501ms. 인기순 충남은 50,505ms였다.
	 * V51 인덱스는 region을 등치 선두로 두어 <b>인덱스 엔트리 21개</b>로 끝낸다 — 위 전부 0.1~0.3ms다.
	 *
	 * <p>옵티마이저는 이 인덱스가 있어도 <b>스스로 고르지 못한다</b>(1M 제주 최신순: 힌트 없이 807ms —
	 * 정렬 인덱스를 계속 고른다). 그래서 지목이 필요하다.
	 *
	 * <p><b>왜 단일 지역만인가</b>: region이 IN(2개 이상)이면 구간이 쪼개져 정렬을 다시 해야 한다.
	 * 밀집 조합(SEOUL,GYEONGGI)에 강제하면 334,344행을 정렬해 <b>1,339ms</b>(강제 안 하면 0.19ms).
	 * 다중 지역은 옵티마이저 판단이 옳으므로 손대지 않는다.
	 *
	 * <p><b>비지역 경로에는 힌트를 붙이지 않는다</b> — 붙일 이유가 측정되지 않았다. 1M에서 비지역
	 * 17경로(정렬 3축·섹션 9조합·검색·카테고리·커서 깊이)를 힌트 유무로 재보니 계획이 전부 동일하고
	 * 최대 차이가 0.10ms↔0.12ms였다. 오히려 힌트를 뺀 쪽이 빠른 경로도 있었다(K1 3.36→0.21ms).
	 *
	 * <p><b>왜 이 표현인가</b>: Hibernate {@code addQueryHint}는 MySQL 방언에서 {@code /*+ … *}{@code /}
	 * 옵티마이저 힌트 주석이 아니라 <b>{@code USE INDEX (…)} 테이블 힌트</b>로 렌더된다
	 * ({@code MySQLDialect#getQueryHintString} → {@code addUseIndexQueryHint}). 이름이 틀리면 MySQL이
	 * <b>ERROR 1176으로 실패</b>한다(옵티마이저 힌트 주석은 경고만 남기고 조용히 무시된다) — 조용히 죽지 않는다.
	 * 단 {@code USE INDEX}는 <b>풀 테이블 스캔을 막지 못한다</b>(위 1,339ms가 실제로 Table scan이었다).
	 *
	 * <p><b>언제 지울 수 있나</b>: MySQL이 단일 지역에서 V51 인덱스를 스스로 고르게 되면 불필요해진다.
	 * 판정: 힌트를 걷어내고 {@code loadtest/probe/explain.sql}의 지역 목록 계획이 100만 행에서
	 * {@code idx_exhibitions_region_*}인지 확인한다.
	 */
	static final String REGION_LATEST_INDEX = "idx_exhibitions_region_start_id";
	static final String REGION_ENDING_INDEX = "idx_exhibitions_region_end_id";
	static final String REGION_POPULAR_INDEX = "idx_exhibitions_region_views_id";

	/**
	 * <b>지역이 2개 이상일 때만</b> count가 타야 하는 V50 커버링 인덱스. count SQL에 {@code USE INDEX (…)}로 실린다.
	 *
	 * <p><b>왜 다중 지역에만 필요한가</b>: region IN이 2개가 되는 순간 옵티마이저가 커버링 인덱스를 버리고
	 * {@code idx_exhibitions_type_owner}(ref, key_len 82 — type 한 컬럼)로 도망간다. "Using index"가 사라져
	 * 행마다 테이블을 찾아가고, 1M 실측 서울·경기 count가 <b>1,919ms</b>다. 커버링을 지목하면 range·key_len 174·
	 * Using index로 <b>350ms</b>(5.5배). 무필터·단일 지역·다중 카테고리는 힌트 없이도 스스로 커버링을 고른다
	 * (1M EXPLAIN 확인) — 붙일 이유가 측정되지 않았으므로 붙이지 않는다.
	 *
	 * <p><b>왜 FORCE INDEX가 아니라 USE INDEX인가</b>: Hibernate {@code addQueryHint}는 MySQL 방언에서
	 * {@code USE INDEX} 테이블 힌트로만 렌더된다(FORCE 문법 채널이 없다). 1M에서 두 문법의 실행 계획이
	 * <b>동일함</b>을 확인했다(range · key_len 174 · Using index) — 다른 인덱스 후보만 제거되면 ORDER BY 없는
	 * count에서 커버링 스캔이 풀 테이블 스캔을 비용으로 이기므로, USE INDEX로 같은 계획 고정이 성립한다.
	 * 목록 슬라이스의 "USE INDEX가 풀 스캔을 못 막은" 사례(1,339ms)는 <b>정렬 비용</b>이 스위치였다 —
	 * count에는 정렬이 없어 그 스위치가 없다.
	 *
	 * <p><b>키워드가 끼면 붙이지 않는다</b>: 키워드 술어(title LIKE + 전시장 서브쿼리)는 커버링 밖 컬럼이라
	 * "Using index"가 성립할 수 없고, 지목하면 옵티마이저가 <b>풀 테이블 스캔(type=ALL)</b>으로 전환한다
	 * (1M EXPLAIN 확인). 키워드 count는 어차피 어떤 B-tree도 못 돕는 경로다(V50 주석 "못 하는 것").
	 *
	 * <p><b>언제 지울 수 있나</b>: MySQL이 다중 지역 IN에서 커버링을 스스로 고르게 되면 불필요해진다.
	 * 판정: 힌트를 걷어내고 {@code loadtest/probe/explain.sql}의 다중지역 count 계획(C3)이 100만 행에서
	 * {@code idx_exhibitions_count_cover · Using index}인지 확인한다.
	 */
	static final String COUNT_COVER_INDEX = "idx_exhibitions_count_cover";

	private final ExhibitionJpaRepository jpaRepository;
	private final EntityManager entityManager;

	/**
	 * 키셋 한 페이지 — <b>count 없이</b> 목록만 가져온다.
	 *
	 * <p>예전엔 {@code findAll(spec, Pageable)}을 쓰고 {@code getContent()}만 꺼냈는데, 그 메서드는 {@code Page}를
	 * 만들며 <b>총 건수 count를 함께 실행</b>한다(우리는 그 값을 버린다). Spring Data가 "첫 페이지이고 결과가
	 * 페이지 크기보다 적으면" 생략해 주기 때문에 데이터가 적을 땐 보이지 않다가, <b>페이지가 꽉 차는 순간</b>
	 * — 즉 다음 페이지가 있는 실사용에서 — 매 요청 count가 두 번 나갔다(하나는 여기, 하나는 totalCount용).
	 *
	 * <p>Spring Data의 fluent query({@code findBy})로 정렬·상한만 걸던 것을 Criteria로 직접 조립한다.
	 * 조건은 여전히 {@link ExhibitionSpecifications#slice}가 만든다(count와 <b>같은 출처</b>) —
	 * 바뀐 것은 인덱스 힌트를 붙일 자리를 얻으려고 {@code Query} 객체를 직접 잡는다는 점뿐이다
	 * (fluent query에는 힌트를 걸 통로가 없다). 이 경로의 힌트는 <b>지역이 정확히 1개일 때만</b> 붙는다 —
	 * count는 반대로 V50 커버링 인덱스를 타야 하며, 그쪽은 <b>지역 2개 이상</b>일 때만 지목한다
	 * ({@link #countCoverIndexFor}). 두 경로가 가리키는 인덱스가 서로 다르다.
	 *
	 * <p><b>SELECT 목록을 좁히지 마라.</b> 여기서 엔티티 전체(전 매핑 컬럼)를 읽는 것은 성능상 필수다.
	 * 투영을 {@code id}만으로 좁히면 정렬 인덱스가 "커버링"이 되면서 옵티마이저가 통과 행 수를
	 * 42로 추정하고 전 인덱스 주사로 전환한다 — 1M 실측 0.283ms → <b>6,824ms(24,100배)</b>.
	 * {@code ExhibitionListIndexHintTest}가 이 투영이 커버 불가임을 매 빌드에서 고정한다.
	 */
	@Override
	public List<Exhibition> searchSlice(ExhibitionQuery query, int limitPlusOne) {
		ExhibitionSort sort = resolveSort(query.sort());
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Exhibition> cq = cb.createQuery(Exhibition.class);
		Root<Exhibition> root = cq.from(Exhibition.class);
		cq.select(root);
		Predicate where = ExhibitionSpecifications.slice(query).toPredicate(root, cq, cb);
		if (where != null) {
			cq.where(where);
		}
		cq.orderBy(orderFor(sort, root, cb));

		TypedQuery<Exhibition> typed = entityManager.createQuery(cq).setMaxResults(Math.max(1, limitPlusOne));
		String hint = regionSortIndexFor(query, sort);
		if (hint != null) {
			typed.unwrap(org.hibernate.query.Query.class).addQueryHint(hint);
		}
		return typed.getResultList();
	}

	/**
	 * 지역이 <b>정확히 1개</b>일 때 그 정렬 축을 서비스하는 V51 복합 인덱스 이름. 그 밖은 {@code null}(힌트 없음).
	 *
	 * <p>0개면 붙일 이유가 없고(비지역 경로는 힌트 유무로 계획이 같다), 2개 이상이면 붙이면 해롭다
	 * (구간이 쪼개져 정렬이 되살아난다 — 밀집 조합에서 1,339ms). 이름이 틀리면 MySQL이 ERROR 1176으로
	 * 실패한다(조용히 안 죽는다).
	 */
	static String regionSortIndexFor(ExhibitionQuery query, ExhibitionSort sort) {
		if (query.regions() == null || query.regions().size() != 1) {
			return null;
		}
		return switch (resolveSort(sort)) {
			case ENDING -> REGION_ENDING_INDEX;
			case POPULAR -> REGION_POPULAR_INDEX;
			// 거리순은 DB가 정렬하지 않아 LATEST로 수렴한다(아래 resolveSort).
			default -> REGION_LATEST_INDEX;
		};
	}

	/** DB가 정렬하지 않는 축(거리순)·null은 기본 축으로 수렴한다 — ORDER BY와 힌트가 같은 값을 봐야 한다. */
	private static ExhibitionSort resolveSort(ExhibitionSort sort) {
		return sort == null || !sort.sortedByDatabase() ? ExhibitionSort.LATEST : sort;
	}

	/**
	 * 총 건수 — 조건은 {@link ExhibitionSpecifications#filter}가 만든다(슬라이스와 <b>같은 출처</b>, 파라미터 drift 방지).
	 *
	 * <p>예전엔 {@code jpaRepository.count(spec)}였는데, Spring Data 경유로는 인덱스 힌트를 걸 통로가 없어
	 * 슬라이스와 같은 방식으로 Criteria를 직접 조립한다. 실행 SQL은 힌트를 빼면 이전과 동일하다
	 * ({@code select count(id) from exhibitions where …}). 힌트는 <b>지역 2개 이상 + 키워드 없음</b>일 때만
	 * 붙는다 — 범위 근거는 {@link #COUNT_COVER_INDEX} 주석.
	 */
	@Override
	public long count(ExhibitionQuery query) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<Exhibition> root = cq.from(Exhibition.class);
		cq.select(cb.count(root));
		Predicate where = ExhibitionSpecifications.filter(query).toPredicate(root, cq, cb);
		if (where != null) {
			cq.where(where);
		}

		TypedQuery<Long> typed = entityManager.createQuery(cq);
		String hint = countCoverIndexFor(query);
		if (hint != null) {
			typed.unwrap(org.hibernate.query.Query.class).addQueryHint(hint);
		}
		return typed.getSingleResult();
	}

	/**
	 * 지역이 <b>2개 이상이고 키워드가 없을 때</b> count가 탈 V50 커버링 인덱스 이름. 그 밖은 {@code null}(힌트 없음).
	 *
	 * <p>0·1개면 옵티마이저가 스스로 커버링을 고르고(1M EXPLAIN 확인 — 붙일 이유가 측정되지 않았다),
	 * 키워드가 끼면 커버링이 원리적으로 성립하지 않아 지목이 풀 스캔 전환으로 해롭다. 이름이 틀리면
	 * MySQL이 ERROR 1176으로 실패한다(조용히 안 죽는다).
	 */
	static String countCoverIndexFor(ExhibitionQuery query) {
		if (query.regions() == null || query.regions().size() < 2) {
			return null;
		}
		if (query.keyword() != null && !query.keyword().isBlank()) {
			return null;
		}
		return COUNT_COVER_INDEX;
	}

	@Override
	public List<Exhibition> searchAll(ExhibitionQuery query) {
		return jpaRepository.findAll(ExhibitionSpecifications.filter(query));
	}

	@Override
	public List<Exhibition> findOngoingCatalogTopByViews(LocalDate onDate, int limit) {
		return jpaRepository.findOngoingCatalogTopByViews(ExhibitionType.CATALOG, onDate,
				Exhibition.START_DATE_UNKNOWN, Exhibition.END_DATE_UNKNOWN,
				PageRequest.of(0, Math.max(1, limit)));
	}

	/**
	 * 정렬 축 → (정렬컬럼, id) 결정적 정렬. 컬럼·방향은 {@link ExhibitionSort}가 들고 있어
	 * 키셋 경계(Specification)와 <b>같은 출처</b>를 본다 — 둘이 어긋나면 행이 누락·중복된다.
	 */
	private static List<Order> orderFor(ExhibitionSort resolved, Root<Exhibition> root, CriteriaBuilder cb) {
		return resolved.ascending()
				? List.of(cb.asc(root.get(resolved.property())), cb.asc(root.get("id")))
				: List.of(cb.desc(root.get(resolved.property())), cb.desc(root.get("id")));
	}
}
