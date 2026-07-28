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
import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
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

		// ongoingOn: 해당 날짜 진행 중. 시작/종료일이 없으면 각각 "이미 시작"/"아직 진행"으로 관대하게 취급.
		if (query.ongoingOn() != null) {
			predicates.add(cb.or(
					cb.isNull(root.get("startDate")),
					cb.lessThanOrEqualTo(root.get("startDate"), query.ongoingOn())));
			predicates.add(cb.or(
					cb.isNull(root.get("endDate")),
					cb.greaterThanOrEqualTo(root.get("endDate"), query.ongoingOn())));
		}

		// notEndedOn: 아직 끝나지 않은 전시만(시작일은 보지 않는다 — 아직 열지 않은 전시는 포함).
		// 검색 전용 조건이다: 이름으로 찾을 땐 다음 달 개막 전시도 찾되, 이미 끝난 전시는 내보내지 않는다.
		if (query.notEndedOn() != null) {
			predicates.add(cb.or(
					cb.isNull(root.get("endDate")),
					cb.greaterThanOrEqualTo(root.get("endDate"), query.notEndedOn())));
		}

		// region: 전시가 아니라 전시장의 속성으로 이동 → exhibition_place 서브쿼리로 필터한다.
		if (query.regions() != null && !query.regions().isEmpty()) {
			Subquery<Long> regionSub = cq.subquery(Long.class);
			Root<ExhibitionPlace> p = regionSub.from(ExhibitionPlace.class);
			regionSub.select(p.get("id")).where(p.get("region").in(query.regions()));
			predicates.add(root.get("exhibitionPlaceId").in(regionSub));
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
			// C-6 무료 규칙의 SQL 근사 — "무료" 포함 또는 정확히 "0원". 가격은 exhibition_detail로 이동해
			// 서브쿼리(id in select exhibition_id ...)로 잇는다. ("20,000원"이 '%0원%'에 걸리는 오탐을 피해 like가 아닌 equal.)
			case FREE -> {
				Subquery<Long> detailSub = cq.subquery(Long.class);
				Root<ExhibitionDetail> d = detailSub.from(ExhibitionDetail.class);
				detailSub.select(d.get("exhibitionId")).where(cb.or(
						cb.like(cb.lower(d.get("price")), "%무료%"),
						cb.equal(d.get("price"), "0원")));
				predicates.add(root.get("id").in(detailSub));
			}
		}
	}

	/**
	 * 키셋 경계 — 정렬 순서상 커서 행보다 "뒤"인 행만. 최종 타이브레이커는 id(페이지 밀림 방지).
	 * cursorId가 null이면(첫 페이지) 경계 없음. cursorKey가 null이면 정렬 컬럼값이 null인 경계(nulls last 블록).
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
		if (key == null) {
			// 커서가 null-시작일 블록에 있음 → startDate null이고 id 더 작은 행만
			return cb.and(cb.isNull(root.get("startDate")), cb.lessThan(root.<Long>get("id"), id));
		}
		LocalDate startDate = LocalDate.parse(key);
		// startDate desc nulls last, id desc → 커서 행보다 뒤: 시작일이 더 이르거나(desc), null(nulls last), 같으면 id가 더 작은 행.
		return cb.or(
				cb.lessThan(root.<LocalDate>get("startDate"), startDate),
				cb.isNull(root.get("startDate")),
				cb.and(cb.equal(root.get("startDate"), startDate), cb.lessThan(root.<Long>get("id"), id)));
	}

	private static Predicate endingBoundary(jakarta.persistence.criteria.Root<Exhibition> root,
			jakarta.persistence.criteria.CriteriaBuilder cb, String key, Long id) {
		if (key == null) {
			return cb.and(cb.isNull(root.get("endDate")), cb.greaterThan(root.<Long>get("id"), id));
		}
		LocalDate endDate = LocalDate.parse(key);
		return cb.or(
				cb.greaterThan(root.<LocalDate>get("endDate"), endDate),
				cb.isNull(root.get("endDate")),
				cb.and(cb.equal(root.get("endDate"), endDate), cb.greaterThan(root.<Long>get("id"), id)));
	}
}
