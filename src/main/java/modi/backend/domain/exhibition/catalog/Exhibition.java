package modi.backend.domain.exhibition.catalog;

import modi.backend.domain.exhibition.hours.PlaceHours;

import java.time.LocalDate;
import java.util.Optional;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.support.entity.BaseEntity;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 전시(애그리거트 루트). 두 출처를 하나의 테이블로 다룬다(CUSTOM 독립 엔티티 모델링).
 * <ul>
 *   <li>CATALOG: 외부 전시 API에서 동기화. {@code externalId}(원천 seq)로 upsert, {@code ownerId=null} → 전체 공개.</li>
 *   <li>CUSTOM: 사용자가 직접 등록. {@code ownerId}=등록자 → 등록자 본인에게만 노출.</li>
 * </ul>
 *
 * <p><b>코어는 생성 시점에 완결된다</b>(ADR-02·03): 전 컬럼이 목록(list) 소스에서 오거나 등록 입력이라 생성과 동시에 확정된다.
 * 지연 도착 정보는 집합체 밖으로 나갔다 — 장소(name/region/gps/주소)는 {@link ExhibitionPlace}(N:1), 상세(price/description/
 * img)는 {@link ExhibitionDetail}(1:1), 영업시간은 {@link PlaceHours}, 장르는 {@link ExhibitionGenre}, 작가는
 * {@link Artist}+{@link ExhibitionArtist}(N:M). "부재"는 코어의 null이 아니라 <b>연관의 부재</b>로 표현한다.
 *
 * <p>의도된 판별 null은 둘뿐이다: {@code ownerId}(CUSTOM만), {@code externalId}·{@code detailUrl}·{@code serviceName}
 * (CATALOG만 — 목록 소스라 생성 시점 확정, ADR-02 "부재는 타입으로"). 상태 변경은 이 Entity 메서드 안에서만 한다.
 */
@Entity
@Table(name = "exhibitions")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Exhibition extends BaseEntity {

	private static final int TITLE_MAX_LENGTH = 100;

	/**
	 * 금액 표기 — "2,000원" / "5000 원". SQL 백필의 {@code [1-9][0-9,]*[[:space:]]*원}과 짝이다
	 * (여기선 값을 뽑아 0인지 보고, SQL은 선두 [1-9]로 같은 판정을 낸다).
	 */
	private static final java.util.regex.Pattern PAID_AMOUNT =
			java.util.regex.Pattern.compile("([0-9][0-9,]*)\\s*원");

	/**
	 * 시작일 미상 센티널 — <b>저장값</b>이다. 의미는 "이미 시작"(과거 무한).
	 *
	 * <p>예전엔 {@code start_date}가 NULL이었고 술어가 {@code (start_date IS NULL OR start_date <= ?)}였다.
	 * OR + IS NULL은 범위 스캔이 성립하지 않아 인덱스가 붙지 못한다(V47). 값으로 바꾸면 술어가
	 * {@code start_date <= ?} 단순 범위가 된다.
	 *
	 * <p>MySQL이 문서로 보장하는 DATE 하한이라 이 값을 골랐다({@code '0001-01-01'}은 지원 범위 밖이고
	 * DATE 산술이 zero date로 넘어갈 수 있다). {@link java.time.LocalDate} 왕복은 무손실이다.
	 */
	public static final LocalDate START_DATE_UNKNOWN = LocalDate.of(1000, 1, 1);

	/** 종료일 미상 센티널 — 저장값. 의미는 "아직 진행"(미래 무한). MySQL이 보장하는 DATE 상한. */
	public static final LocalDate END_DATE_UNKNOWN = LocalDate.of(9999, 12, 31);

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ExhibitionType type;

	/** 외부 전시 API의 원천 식별자(seq). CATALOG 동기화 upsert 기준키. CUSTOM은 null(의도된 판별 null). */
	@Column(name = "external_id", length = 100)
	private String externalId;

	/** CUSTOM 전시의 등록자. CATALOG는 null(공개, 의도된 판별 null). */
	@Column(name = "owner_id")
	private Long ownerId;

	@Column(nullable = false, length = TITLE_MAX_LENGTH)
	private String title;

	/** 전시장(N:1) 참조 — 경계 넘는 FK 아님, ID 논리 참조. 생성 시점 확정이라 NOT NULL(ADR-05·06). */
	@Column(name = "exhibition_place_id", nullable = false)
	private Long exhibitionPlaceId;

	/**
	 * 지역 — 전시장({@link ExhibitionPlace#getRegion()})의 <b>적재 시점 스냅샷</b>이다(V49).
	 *
	 * <p>정규화 원칙대로면 전시장을 조인해 읽어야 하지만, 그러면 지역 필터가 매 요청
	 * {@code exhibition_place_id IN (SELECT id FROM exhibition_place WHERE region IN (...))}
	 * 서브쿼리가 된다. 이 복제로 술어가 {@code region IN (...)} 단일 테이블 조건이 됐다.
	 *
	 * <p><b>갱신하지 않는다</b>(사용자 결정) — "그때의 전시를 그대로 저장하는 것"이다. 전시장 region이
	 * 나중에 바뀌어도 이 값은 그대로 둔다. 그래서 <b>표시용 region도 이 값을 쓴다</b>
	 * ({@code ExhibitionResult}) — 필터는 복제본, 표시는 조인이면 검색 결과와 화면이 어긋난다.
	 *
	 * <p>NULL 허용: 전시장 region 자체가 nullable이다(CUSTOM 등록은 지역 미지정 가능).
	 * 옛 서브쿼리도 region이 NULL인 전시장을 걸러냈으므로 판정 결과는 같다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "region", length = 20)
	private ExhibitionRegion region;

	/**
	 * 무료 여부 — 상세 가격({@link ExhibitionDetail#getPrice()})을 {@link #isFreePrice(String)}로 판정해 굳힌 값(V49).
	 *
	 * <p>옛 술어는 {@code price LIKE '%무료%'}였다. 선행 와일드카드라 인덱스가 원천 불가이고,
	 * 가격이 satellite에 있어 서브쿼리까지 붙었다 — 무료 필터 count가 무필터의 2배가 된 원인이다.
	 *
	 * <p><b>가격 원본은 지우지 않는다</b>: 판정 규칙이 바뀌면 V49의 UPDATE를 다시 돌려 재계산한다.
	 * 값을 굳히는 진짜 이득은 인덱스가 아니라 <b>판정이 SQL LIKE에서 도메인 코드로 옮겨온 것</b>이다 —
	 * 단위 테스트가 가능해지고 규칙을 정교하게 만들 수 있다.
	 *
	 * <p>가격이 도착하는 지점마다 {@link #applyPriceJudgement(String)}로 다시 굳힌다(승격·관리자 수정).
	 * 상세가 아직 안 온 전시는 false다 — 옛 규칙도 detail 행이 없으면 무료로 잡지 않았다.
	 */
	@Column(name = "is_free", nullable = false)
	private boolean free;

	/**
	 * 시작일 — <b>저장은 NOT NULL</b>이고 미상은 {@link #START_DATE_UNKNOWN}으로 적는다(V47).
	 * 밖으로 나가는 값은 {@link #getStartDate()}가 다시 null로 되돌린다 — 필드를 직접 읽지 마라.
	 */
	@Getter(AccessLevel.NONE)
	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	/** 종료일 — 위와 같다. 미상은 {@link #END_DATE_UNKNOWN}. */
	@Getter(AccessLevel.NONE)
	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private ExhibitionCategory category;

	/** 전시 형태(개인전/단체전/기획전/아트페어). CUSTOM 등록 시 선택. CATALOG는 null(원천 미보유). */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private ExhibitionFormat format;

	/** 포스터 이미지 URL(목록 thumbnail 소스 — 코어 잔류, 설계 §1 교정). 없으면 null. */
	@Column(name = "poster_url", length = 2048)
	private String posterUrl;

	/** 원문 상세 페이지 링크. CATALOG 목록 소스, CUSTOM은 null. */
	@Column(name = "detail_url", length = 2048)
	private String detailUrl;

	/** 제공(연계) 기관명. CATALOG 목록 소스, CUSTOM은 null. */
	@Column(name = "service_name", length = 200)
	private String serviceName;

	/** 우리 앱 내 조회수(인기순 정렬용). 외부 API의 조회수와 별개. */
	@Column(name = "our_view_count", nullable = false)
	private long ourViewCount = 0;

	private Exhibition(ExhibitionType type, String externalId, Long ownerId, String title, Long exhibitionPlaceId,
			ExhibitionRegion region, LocalDate startDate, LocalDate endDate, ExhibitionCategory category,
			ExhibitionFormat format, String artist, String posterUrl, String detailUrl, String serviceName) {
		this.type = type;
		this.externalId = externalId;
		this.ownerId = ownerId;
		this.title = requireTitle(title);
		this.exhibitionPlaceId = exhibitionPlaceId;
		// 전시장 지역의 스냅샷 — 호출부가 방금 resolve한 전시장의 값을 그대로 넘긴다.
		this.region = region;
		// 미상(null)을 센티널로 정규화하는 유일한 지점이다. 수집(ingestion)·개인 등록·데모 시더가 전부
		// createCatalog/createCustom을 거치므로 여기 하나로 전 쓰기 경로가 덮인다.
		// 수집 쪽은 센티널을 몰라야 한다(ADR-12 — 계약 어휘는 코어 소유) → 계약은 계속 null을 넘긴다.
		this.startDate = startDate == null ? START_DATE_UNKNOWN : startDate;
		this.endDate = endDate == null ? END_DATE_UNKNOWN : endDate;
		this.category = category;
		this.format = format;
		this.posterUrl = posterUrl;
		this.detailUrl = detailUrl;
		this.serviceName = serviceName;
		validatePeriod();
		validateSoloArtist(format, artist);
	}

	/**
	 * 사용자 개인 전시(CUSTOM) 등록. 제목 필수, 기간·개인전 작가 검증. {@code exhibitionPlaceId}는 Facade가 전시장을
	 * resolve-or-create(정규화 이름)해 넘긴다. {@code artist}는 SOLO 검증에만 쓰고 코어에 저장하지 않는다 —
	 * 작가는 {@link Artist}+{@link ExhibitionArtist}에 별도 저장한다(Facade가 조율).
	 */
	public static Exhibition createCustom(Long ownerId, String title, Long exhibitionPlaceId, ExhibitionRegion region,
			LocalDate startDate, LocalDate endDate, ExhibitionCategory category, ExhibitionFormat format,
			String artist, String posterUrl) {
		return new Exhibition(ExhibitionType.CUSTOM, null, ownerId, title, exhibitionPlaceId, region, startDate,
				endDate, category, format, artist, posterUrl, null, null);
	}

	/**
	 * 외부 API 수집 전시(CATALOG) 생성. {@code externalId}는 동기화 upsert 기준키. 상세(price 등)는 별도 satellite로 지연 채움.
	 * {@code region}은 호출부가 방금 resolve한 전시장의 지역이다(적재 시점 스냅샷 — {@link #region} 참조).
	 * 무료 여부는 상세가 따라오는 지점에서 {@link #applyPriceJudgement(String)}로 굳힌다.
	 */
	public static Exhibition createCatalog(String externalId, String title, Long exhibitionPlaceId,
			ExhibitionRegion region, LocalDate startDate, LocalDate endDate, ExhibitionCategory category,
			String posterUrl, String detailUrl, String serviceName) {
		return new Exhibition(ExhibitionType.CATALOG, externalId, null, title, exhibitionPlaceId, region, startDate,
				endDate, category, null, null, posterUrl, detailUrl, serviceName);
	}

	/**
	 * 관리자 수정 — 전시장 재지정(place 이름 변경 시 Facade가 새 전시장을 resolve-or-create해 넘긴다).
	 * <b>지역 스냅샷도 함께 옮긴다</b> — 다른 전시장으로 옮겼는데 지역만 옛 값이면 필터와 표시가 둘 다 틀린다.
	 * (전시장 <i>자신의</i> region이 바뀌는 경우는 따라가지 않는다 — 그건 스냅샷 결정 그대로다.)
	 */
	public void reassignPlace(Long exhibitionPlaceId, ExhibitionRegion region) {
		this.exhibitionPlaceId = exhibitionPlaceId;
		this.region = region;
	}

	/**
	 * 가격 텍스트로 무료 여부를 다시 굳힌다(멱등). 가격이 코어에 닿는 <b>모든 지점</b>이 이걸 부른다:
	 * 승격 등록(수집이 완성한 price)과 관리자 가격 수정. 그 둘뿐이라 값이 조용히 낡을 자리가 없다.
	 *
	 * <p>규칙 자체는 {@link #isFreePrice(String)}에 있다 — 여기서는 판정을 저장할 뿐이다.
	 */
	public void applyPriceJudgement(String price) {
		this.free = isFreePrice(price);
	}

	/**
	 * 관리자 수정 — 제목이 실제로 바뀌면 변경 이력을 돌려준다(멱등). null 인자는 "건드리지 않음".
	 * (place는 전시장, price·description은 상세로 분리돼 각 엔티티가 자기 변경을 판단한다.)
	 */
	public Optional<FieldChange> applyTitleEdit(String title) {
		if (title == null || java.util.Objects.equals(this.title, title)) {
			return Optional.empty();
		}
		FieldChange change = new FieldChange("title", this.title, title);
		this.title = title;
		return Optional.of(change);
	}

	/** 우리 앱 내 조회 1회 발생 시 호출(인기순 정렬용 카운터). */
	public void increaseView() {
		this.ourViewCount += 1;
	}

	/**
	 * 시작일 — <b>미상이면 null</b>. 센티널은 저장 표현일 뿐이라 여기서 도로 걷어낸다.
	 * 응답·기록 스냅샷·프롬프트처럼 <b>밖으로 나가는 값은 반드시 이것</b>을 쓴다
	 * (필드를 그대로 내보내면 {@code 1000-01-01}이 사용자에게 보인다).
	 */
	public LocalDate getStartDate() {
		return START_DATE_UNKNOWN.equals(startDate) ? null : startDate;
	}

	/** 종료일 — 미상이면 null. 위와 같다. */
	public LocalDate getEndDate() {
		return END_DATE_UNKNOWN.equals(endDate) ? null : endDate;
	}

	/**
	 * 시작일의 <b>정렬 키</b> — 저장값 그대로(미상이면 센티널). 항상 non-null.
	 *
	 * <p>정렬·커서 경계 전용이다. ORDER BY는 DB의 저장값을 세우므로 커서에 실리는 값도 같은 값이어야
	 * 경계와 순서가 어긋나지 않는다({@link ExhibitionSort}). <b>응답에 싣지 마라.</b>
	 */
	public LocalDate startDateKey() {
		return startDate;
	}

	/** 종료일의 정렬 키 — 저장값 그대로(미상이면 센티널). 항상 non-null. 응답에 싣지 마라. */
	public LocalDate endDateKey() {
		return endDate;
	}

	public boolean isCatalog() {
		return type == ExhibitionType.CATALOG;
	}

	/**
	 * <b>무료 판정 규칙</b>(C-6) — 가격 텍스트 하나를 보고 "이 전시는 무료인가"를 답한다.
	 * 가격은 상세({@link ExhibitionDetail})에 있어 호출부가 값을 넘긴다. null/공백(가격 미상)은 무료가 아니다.
	 *
	 * <p><b>규칙</b>: 0이 아닌 금액 표기(숫자+원)가 하나라도 있으면 유료다. 그렇지 않을 때
	 * "무료"가 들어 있거나 표기된 숫자가 전부 0이면 무료다.
	 *
	 * <pre>
	 *   무료                                        → true
	 *   무료 *단체관람은 홈페이지 신청 필수                    → true
	 *   무료 / ※ 경복궁 관람료 별도                        → true
	 *   0원                                        → true  (실데이터엔 없다 — 옛 규칙 보존)
	 *   성인 2,000원 / … 노인 및 유아 무료                  → false ← 옛 규칙은 이걸 무료로 잡았다
	 *   현장등록 5,000원 (사전등록시 무료입장)                 → false ←   〃
	 *   미정 · 해당사항 없음                              → false
	 * </pre>
	 *
	 * <p><b>왜 유료 금액을 먼저 보는가</b>: 옛 규칙 {@code LIKE '%무료%'}는 부분 무료("노인 및 유아 무료")를
	 * 전체 무료로 잡았다. 원본 313건 중 13건(4.2%)이 그런 오탐이었다. 무료를 찾는 사용자에게
	 * 성인 23,000원짜리 전시를 보여주던 것이라 <b>고의로 바꾼 동작</b>이다.
	 *
	 * <p><b>이 규칙은 {@code V49__denormalize_exhibition_region_and_free.sql}의 백필 SQL과 같아야 한다.</b>
	 * 어긋나면 기존 행(SQL이 굳힌 값)과 신규 행(이 코드가 굳힌 값)이 다른 규칙을 타게 된다.
	 * {@code ExhibitionFreeRuleTest}가 실데이터 57종 가격 문자열로 둘의 일치를 고정한다.
	 *
	 * <p><b>한계</b>: "1만5천원"처럼 한글 수사로 적힌 금액은 유료 표기로 인식하지 못한다. 실데이터에 1건 있고
	 * 그 행엔 "무료"가 없어 판정은 옳게 나오지만, "1만5천원 / 어린이 무료" 형태가 오면 무료로 오판한다.
	 * 규칙을 고치면 원본 price로 재계산하면 된다 — 그래서 price를 지우지 않는다.
	 */
	public static boolean isFreePrice(String price) {
		if (price == null || price.isBlank()) {
			return false;
		}
		if (hasPaidAmount(price)) {
			return false;
		}
		if (price.contains("무료")) {
			return true;
		}
		String digits = price.replaceAll("[^0-9]", "");
		return digits.matches("0+");
	}

	/** "숫자[,숫자]* 원" 중 0이 아닌 것이 하나라도 있는가. 콤마는 자릿수 구분자라 떼고 본다. */
	private static boolean hasPaidAmount(String price) {
		java.util.regex.Matcher matcher = PAID_AMOUNT.matcher(price);
		while (matcher.find()) {
			if (!matcher.group(1).replace(",", "").matches("0+")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 종료 D-데이(오늘로부터 종료일까지 남은 일수). 종료일이 없거나 이미 종료됐으면 null. (오늘 == 종료일이면 D-0)
	 */
	public Integer dDay(LocalDate today) {
		// 센티널을 걷어낸 값으로 잰다 — 필드를 그대로 쓰면 종료일 미상이 D-292만일로 나간다.
		LocalDate end = getEndDate();
		if (end == null || end.isBefore(today)) {
			return null;
		}
		return (int) java.time.temporal.ChronoUnit.DAYS.between(today, end);
	}

	/** 요청자가 이 전시를 조회할 수 있는가. CATALOG는 공개, CUSTOM은 등록자 본인만. */
	public boolean isAccessibleBy(Long requesterId) {
		return isCatalog() || (requesterId != null && requesterId.equals(ownerId));
	}

	/**
	 * 요청자가 직접 등록한 개인(CUSTOM) 전시인가 — 기록 삭제 시 동반 삭제 가능 여부 판단용.
	 * 공용 CATALOG나 타인의 CUSTOM은 삭제 대상이 아니다.
	 */
	public boolean isCustomOwnedBy(Long requesterId) {
		return type == ExhibitionType.CUSTOM && requesterId != null && requesterId.equals(ownerId);
	}

	private String requireTitle(String value) {
		String trimmed = Optional.ofNullable(value).map(String::trim).orElse("");
		if (trimmed.isEmpty() || trimmed.length() > TITLE_MAX_LENGTH) {
			throw new CoreException(ErrorType.INVALID_INPUT, "전시 제목은 1~" + TITLE_MAX_LENGTH + "자여야 합니다: " + value);
		}
		return trimmed;
	}

	/**
	 * {@code RULE: 전시 기간} — startDate ≤ endDate.
	 *
	 * <p>정규화(V47) 이후 두 필드는 항상 값이 있다. 규칙의 <b>판정 결과는 예전과 같다</b>:
	 * 센티널은 과거 무한·미래 무한이라 어느 실제 날짜와 짝지어도 순서를 뒤집지 못하고,
	 * 예전에 "한쪽이 null이면 검증을 건너뛴다"였던 경우가 전부 통과로 남는다.
	 * 즉 이 정규화는 기간 불변식을 느슨하게도 엄격하게도 만들지 않는다.
	 *
	 * <p>다만 그 대가로 "모름"과 "아주 옛날/아주 먼 미래"가 저장값으로는 구분되지 않는다.
	 * 지금은 그 구분이 필요한 규칙이 없어 별도 플래그를 두지 않았다 — 필요해지면 여기부터 본다.
	 */
	private void validatePeriod() {
		if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
			throw new CoreException(ErrorType.INVALID_INPUT,
					"종료일이 시작일보다 앞설 수 없습니다: " + getStartDate() + " ~ " + getEndDate());
		}
	}

	/** {@code RULE: 개인전 작가} — format=SOLO(개인전)면 작가명이 필요하다. */
	private static void validateSoloArtist(ExhibitionFormat format, String artist) {
		if (format == ExhibitionFormat.SOLO && (artist == null || artist.isBlank())) {
			throw new CoreException(ErrorType.INVALID_INPUT, "개인전은 작가명이 필요합니다");
		}
	}

	@Override
	protected void guard() {
		validatePeriod();
	}
}
