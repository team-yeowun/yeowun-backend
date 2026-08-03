package modi.backend.infra.exhibition.catalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionType;

/**
 * {@link ExhibitionQuery} → JPA {@link Specification} 변환. (03_전시.md 5.2 처리 로직)
 * 살아있는 행만, CUSTOM 노출 규칙, keyword/ongoingOn/regions/categories/section 필터를 조합하고(=filter),
 * 커서 페이지네이션의 키셋 경계(=keyset)를 추가 조건으로 얹는다.
 */
final class ExhibitionSpecifications {

	private ExhibitionSpecifications() {
	}

	/** 필터만(정렬·커서 경계 제외) — count·거리순 후보 조회용. */
	static Specification<Exhibition> filter(ExhibitionQuery query) {
		return (root, cq, cb) -> cb.and(filterPredicates(query, root, cq, cb).toArray(Predicate[]::new));
	}

	/** 필터 + 키셋 경계 — 키셋 정렬(latest/ending/popular) 슬라이스 조회용. */
	static Specification<Exhibition> slice(ExhibitionQuery query) {
		return (root, cq, cb) -> {
			List<Predicate> predicates = filterPredicates(query, root, cq, cb);
			Predicate keyset = keyset(query, root, cb);
			if (keyset != null) {
				predicates.add(keyset);
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	private static List<Predicate> filterPredicates(ExhibitionQuery query, Root<Exhibition> root,
			CriteriaQuery<?> cq, CriteriaBuilder cb) {
		List<Predicate> predicates = new ArrayList<>();

		// soft delete 제외
		predicates.add(cb.isNull(root.get("deletedAt")));

		// 노출 범위: 전시탐색은 공개(CATALOG) 전시만 노출한다. 개인(CUSTOM) 전시는 등록자 본인이라도
		// 탐색 목록에 노출하지 않는다 — 개인 전시는 내 기록/아카이브(및 상세 직접 접근)로만 다룬다.
		predicates.add(cb.equal(root.get("type"), ExhibitionType.CATALOG));

		// keyword: 전시명 부분 일치 또는 전시장명 부분 일치(대소문자 무시). 전시장명은 exhibition_place로 이동해
		// 서브쿼리(exhibition_place_id in ...)로 잇는다(경계 넘는 조인 대신 ID 참조 유지).
		if (query.keyword() != null && !query.keyword().isBlank()) {
			String like = "%" + query.keyword().trim().toLowerCase() + "%";
			Subquery<Long> placeSub = cq.subquery(Long.class);
			Root<ExhibitionPlace> p = placeSub.from(ExhibitionPlace.class);
			placeSub.select(p.get("id")).where(cb.like(cb.lower(p.get("name")), like));
			predicates.add(cb.or(
					cb.like(cb.lower(root.get("title")), like),
					root.get("exhibitionPlaceId").in(placeSub)));
		}

		// ongoingOn: 해당 날짜 진행 중. 시작/종료일이 없으면 각각 "이미 시작"/"아직 진행"으로 관대하게 취급하는데,
		// 그 관대함이 이제는 술어가 아니라 저장값에 들어 있다 — 미상은 센티널(과거 무한/미래 무한)로 적힌다(V47).
		// 예전 술어 (start IS NULL OR start <= ?) AND (end IS NULL OR end >= ?)는 OR+IS NULL 탓에
		// 범위 스캔이 성립하지 않아 idx_exhibitions_dates가 붙지 못했다. 판정 결과는 그대로 두고 모양만 단순 범위로 바꾼다.
		if (query.ongoingOn() != null) {
			predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("startDate"), query.ongoingOn()));
			predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("endDate"), query.ongoingOn()));
		}

		// notEndedOn: 아직 끝나지 않은 전시만(시작일은 보지 않는다 — 아직 열지 않은 전시는 포함).
		// 검색 전용 조건이다: 이름으로 찾을 땐 다음 달 개막 전시도 찾되, 이미 끝난 전시는 내보내지 않는다.
		// 종료일 미상은 센티널이 미래 무한이라 그대로 통과한다(예전 IS NULL 분기와 같은 결과).
		if (query.notEndedOn() != null) {
			predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("endDate"), query.notEndedOn()));
		}

		// region: 전시장의 속성이지만 적재 시점에 전시 행으로 복제해 뒀다(V49) → 단일 테이블 IN 조건.
		// 예전엔 exhibition_place_id IN (SELECT id FROM exhibition_place WHERE region IN (...))라
		// 매 요청 전시장 테이블(73만 행)을 훑는 세미조인이 붙었다. 500k 실측 지역 count 1,422ms → 991ms.
		// region이 NULL인 행(전시장 지역 미지정)은 IN에서 빠진다 — 옛 서브쿼리와 같은 판정이다.
		if (query.regions() != null && !query.regions().isEmpty()) {
			predicates.add(root.get("region").in(query.regions()));
		}
		if (query.categories() != null && !query.categories().isEmpty()) {
			predicates.add(root.get("category").in(query.categories()));
		}

		addSectionPredicate(query, root, cq, cb, predicates);

		return predicates;
	}

	/** 섹션 필터 — ending-soon(종료일 창)·opening-this-month(시작일 창)·free(무료 근사 규칙). */
	private static void addSectionPredicate(ExhibitionQuery query, Root<Exhibition> root,
			CriteriaQuery<?> cq, CriteriaBuilder cb, List<Predicate> predicates) {
		if (query.section() == null) {
			return;
		}
		switch (query.section()) {
			case ENDING_SOON -> predicates.add(cb.between(root.<LocalDate>get("endDate"),
					query.sectionFrom(), query.sectionTo()));
			case OPENING_THIS_MONTH -> predicates.add(cb.between(root.<LocalDate>get("startDate"),
					query.sectionFrom(), query.sectionTo()));
			// C-6 무료 규칙은 이제 SQL이 아니라 도메인(Exhibition.isFreePrice)이 판정하고, 그 결과가
			// exhibitions.is_free에 굳어 있다(V49). 예전엔 상세 서브쿼리 + lower(price) LIKE '%무료%'였는데
			// 선행 와일드카드라 인덱스가 원천 불가였다 — 무료 count가 무필터의 2배였던 원인.
			// 500k 실측 무료 count 2,223ms → 1,016ms(무필터와 같은 수준으로 수렴).
			case FREE -> predicates.add(cb.isTrue(root.get("free")));
		}
	}

	/**
	 * 키셋 경계 — 정렬 순서상 커서 행보다 "뒤"인 행만. 최종 타이브레이커는 id(페이지 밀림 방지).
	 * cursorId가 null이면(첫 페이지) 경계 없음. 날짜 축의 cursorKey는 정규화(V47) 이후 항상 값이 있고,
	 * null로 오는 건 정규화 전에 발급된 옛 커서뿐이라 센티널 블록으로 해석한다.
	 * (이름 가나다 타이브레이커는 커서 건전성 위해 보류 — 최종 타이브레이커=id)
	 */
	private static Predicate keyset(ExhibitionQuery query, jakarta.persistence.criteria.Root<Exhibition> root,
			jakarta.persistence.criteria.CriteriaBuilder cb) {
		Long id = query.cursorId();
		if (id == null) {
			return null;
		}
		String key = query.cursorKey();
		return switch (query.sort()) {
			case ENDING -> endingBoundary(root, cb, key, id);
			case POPULAR -> {
				long viewCount = Long.parseLong(key);
				yield cb.or(
						cb.lessThan(root.<Long>get("ourViewCount"), viewCount),
						cb.and(cb.equal(root.get("ourViewCount"), viewCount),
								cb.lessThan(root.<Long>get("id"), id)));
			}
			// latest(기본): startDate desc nulls last, id desc. distance는 DB 정렬이 아니라 커서 경계도 없다.
			default -> latestBoundary(root, cb, key, id);
		};
	}

	private static Predicate latestBoundary(jakarta.persistence.criteria.Root<Exhibition> root,
			jakarta.persistence.criteria.CriteriaBuilder cb, String key, Long id) {
		// key == null은 정규화(V47) 전에 발급된 커서뿐이다 — 그때의 "시작일 미상 블록"이 지금의 센티널 블록이다.
		LocalDate startDate = key == null ? Exhibition.START_DATE_UNKNOWN : LocalDate.parse(key);
		// startDate desc, id desc → 커서 행보다 뒤: 시작일이 더 이르거나(desc), 같으면 id가 더 작은 행.
		// 예전엔 여기에 isNull(startDate) 분기가 하나 더 붙어 있었다(nulls last 블록). 센티널은 어느 실제 날짜보다도
		// 이르므로 첫 분기가 그 블록을 이미 덮는다 — OR가 하나 줄었다.
		return cb.or(
				cb.lessThan(root.<LocalDate>get("startDate"), startDate),
				cb.and(cb.equal(root.get("startDate"), startDate), cb.lessThan(root.<Long>get("id"), id)));
	}

	private static Predicate endingBoundary(jakarta.persistence.criteria.Root<Exhibition> root,
			jakarta.persistence.criteria.CriteriaBuilder cb, String key, Long id) {
		// endDate asc, id asc. 센티널(미래 무한)은 어느 실제 날짜보다도 뒤라 첫 분기가 옛 nulls 블록을 덮는다.
		//
		// 부수 효과: 예전엔 MySQL이 ASC에서 NULL을 <b>앞</b>에 놓는데 이 경계는 null을 <b>뒤</b>로 취급해
		// 종료일 null 행이 섞이면 커서 순회에서 어긋날 수 있었다. 정규화로 그 행들이 실제로 맨 뒤에 서면서
		// 순서와 경계가 같은 뜻이 됐다 — 경계 코드를 고쳐서가 아니라 원인(NULL)이 사라져서다.
		LocalDate endDate = key == null ? Exhibition.END_DATE_UNKNOWN : LocalDate.parse(key);
		return cb.or(
				cb.greaterThan(root.<LocalDate>get("endDate"), endDate),
				cb.and(cb.equal(root.get("endDate"), endDate), cb.greaterThan(root.<Long>get("id"), id)));
	}
}
