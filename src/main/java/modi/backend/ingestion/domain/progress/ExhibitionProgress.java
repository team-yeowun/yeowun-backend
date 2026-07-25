package modi.backend.ingestion.domain.progress;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;

/**
 * 전시 초기화 <b>진행 상태</b> — {@code exhibition_progress} 매핑(구 exhibition_draft, 설계 D1 리네임).
 * <b>초기화 in-flight 상태의 단독 보유자이자 대시보드의 척추</b>다.
 *
 * <p><b>슬림 원칙(설계 §5-2)</b>: 데이터 컬럼을 들지 않는다 — 목록·상세·장르의 값은 전부 스냅샷 원장
 * (culture_list/culture_detail/genre_snapshot)이 갖고, 여기는 <b>어디까지 왔는지</b>(마커)만 남는다.
 * 원장 합류 규칙(마커가 있으면 원장이 반드시 있다 — 같은 tx)이 어셈블이 스냅샷을 읽는 근거다.
 *
 * <p>승격 게이트(설계 확정): 전시장 키({@code place_key} — 스테이징 시 확정) + 상세 스텝 해소
 * ({@code detail_resolved_at}) + 장르 마커({@code genre_classified_at}). 제목 유효성은 스테이징 선행검증
 * ({@link CatalogExhibitionData#isPersistable()})이 보장한다. 영업시간은 게이트에 없다(전시장 축은 승격 비차단).
 *
 * <p>모든 전이는 이 Entity의 메서드 안에서만 일어나고, 동시 완주 경합은 {@link Version 낙관락} +
 * {@code exhibitions.external_id} UK가 멱등을 보장한다. 마커 메서드는 재전달(at-least-once)에 안전하도록
 * 멱등이다(이미 반영된 스텝은 no-op).
 *
 * <p>승격 후에도 행을 보존한다(COMPLETED) — "원래 있던 전시" 판정 근거. 재생성될 수 있는 파이프라인
 * 테이블이라 {@code BaseEntity}를 상속하지 않고 {@code created_at/updated_at}만 자체 관리한다.
 */
@Entity
@Table(name = "exhibition_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExhibitionProgress {

	/** last_error가 무한정 커지지 않게 저장 전 자르는 상한(원인 식별엔 충분하다). */
	private static final int MAX_ERROR_LENGTH = 1000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 원천키 — 전시 승격 시 {@code exhibitions.external_id}가 된다. UK(중복 스테이징 방지). */
	@Column(name = "external_id", nullable = false, length = 255)
	private String externalId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ProgressStatus status;

	/**
	 * 전시장 자연키(정규화 이름, ADR-07) — 전시장 축(PLACE_STAGED)과의 조인 축이자 게이트 재료
	 * ("전시장 있음"의 증명). 원천 장소명이 비면 null — 그 행은 게이트를 영원히 못 채운다(장소 없는 전시 미승격, 기존 의미 보존).
	 */
	@Column(name = "place_key", length = 500)
	private String placeKey;

	// ── 스텝 해소 마커(값은 원장에, 여기는 시각만) ─────────────────────────────────

	/** 상세 스텝 해소 시각 — 값 도착과 "원천 무상세 확인"을 구분하지 않는다(둘 다 스텝 완료). */
	@Column(name = "detail_resolved_at")
	private LocalDateTime detailResolvedAt;

	/** 장르 스텝 해소 시각 — 분류 결과는 {@code genre_snapshot} 원장에 있다. */
	@Column(name = "genre_classified_at")
	private LocalDateTime genreClassifiedAt;

	/** 전시장 축 분기 결과(새/기존) — 대시보드 컬럼(설계 §7). 전시장 축이 도달하기 전엔 null. */
	@Enumerated(EnumType.STRING)
	@Column(name = "place_outcome", length = 20)
	private PlaceOutcome placeOutcome;

	// ── 종료·추적 ────────────────────────────────────────────────────────────────

	/** 승격으로 생성된 전시 id(COMPLETED에서만). 논리 참조 — 진행 상태는 파이프라인 소유물이라 FK를 걸지 않는다. */
	@Column(name = "promoted_exhibition_id")
	private Long promotedExhibitionId;

	/** 마지막 실패 원인(FAILED 가시화 — 관리자 대시보드의 "막힌 사유"). */
	@Column(name = "last_error", columnDefinition = "text")
	private String lastError;

	/** 낙관락 — 상세·장르 스텝이 동시에 완주해 둘 다 승격을 시도하는 경합에서 한쪽만 이긴다. */
	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	/** 종료(COMPLETED·FAILED) 시각. */
	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private ExhibitionProgress(String externalId, String placeKey) {
		this.externalId = externalId;
		this.placeKey = placeKey;
		this.status = ProgressStatus.PENDING;
	}

	/**
	 * 목록 1건을 스테이징한다 — 초기화의 진입점. 전시장 키는 여기서 파생·확정된다(게이트 어휘의 소유자).
	 * 목록 원장 기록·이벤트 발행은 같은 트랜잭션에서 서비스가 조율한다.
	 */
	public static ExhibitionProgress stage(CatalogExhibitionData data) {
		if (data == null || !data.isPersistable()) {
			throw new IllegalArgumentException("externalId·title 없는 원천 데이터는 스테이징할 수 없다");
		}
		return new ExhibitionProgress(data.externalId(), placeKeyOf(data));
	}

	/** 원천 장소명 → 전시장 자연키. 비어 있으면 null(센티널로 만들면 "장소 없음"이 게이트를 통과해버린다). */
	public static String placeKeyOf(CatalogExhibitionData data) {
		String place = data.place();
		return place == null || place.isBlank() ? null : PlaceKey.of(place);
	}

	/** 재sync가 같은 원천을 다시 만났다 — 장소명이 뒤늦게 생겼으면 키를 보강한다(값 갱신은 원장 몫). */
	public void refreshPlaceKey(CatalogExhibitionData data) {
		if (this.status.isTerminal() || this.placeKey != null) {
			return;
		}
		this.placeKey = placeKeyOf(data);
	}

	/** 상세 스텝이 해소됐다(값 도착 또는 무상세 확인 — 원장은 같은 tx에서 Ledger가 기록). 재전달 멱등. */
	public void markDetailResolved(LocalDateTime now) {
		if (this.status.isTerminal() || this.detailResolvedAt != null) {
			return;
		}
		this.detailResolvedAt = now;
		markEnriching();
	}

	/** 장르 스텝이 해소됐다(결과는 genre_snapshot 원장에). 재전달 멱등. */
	public void markGenreClassified(LocalDateTime now) {
		if (this.status.isTerminal() || this.genreClassifiedAt != null) {
			return;
		}
		this.genreClassifiedAt = now;
		markEnriching();
	}

	/** 전시장 축 분기 결과 마크 — 첫 도달만 기록한다(재전달 멱등, 이후 값은 첫 판정이 진실). */
	public void markPlaceOutcome(boolean created) {
		if (this.placeOutcome != null) {
			return;
		}
		this.placeOutcome = created ? PlaceOutcome.NEW : PlaceOutcome.EXISTING;
	}

	/** 다음 스텝 파생 — 저장 상태가 아니라 해소 마커에서 읽는다({@link ProgressStep}). 재sync 보강의 단일 진실. */
	public ProgressStep nextStep() {
		if (this.status.isTerminal()) {
			return ProgressStep.NONE;
		}
		if (needsDetail()) {
			return ProgressStep.FETCH_DETAIL;
		}
		if (needsGenre()) {
			return ProgressStep.CLASSIFY_GENRE;
		}
		if (isReadyForPromotion()) {
			return ProgressStep.PROMOTE;
		}
		return ProgressStep.NONE;
	}

	/** 승격 게이트 — 전시장 키 + 상세 해소 + 장르 마커(제목은 스테이징 선행검증이 보장). */
	public boolean isReadyForPromotion() {
		return !this.status.isTerminal()
				&& this.placeKey != null
				&& this.detailResolvedAt != null
				&& this.genreClassifiedAt != null;
	}

	/** 승격 완료 — 생성된 전시 id를 남기고 종료한다. 게이트 미충족 승격은 프로그래밍 오류다(조용히 넘기지 않는다). */
	public void complete(Long exhibitionId, LocalDateTime now) {
		if (!isReadyForPromotion()) {
			throw new IllegalStateException("승격 게이트 미충족 진행 상태를 종료할 수 없다: " + this.externalId);
		}
		this.status = ProgressStatus.COMPLETED;
		this.promotedExhibitionId = exhibitionId;
		this.lastError = null;
		this.completedAt = now;
	}

	/** 필수 스텝의 영구 실패(4xx·시도 소진) — 승격 불가로 종료한다(관리자 가시화). 이미 종료면 no-op. */
	public void fail(String error, LocalDateTime now) {
		if (this.status.isTerminal()) {
			return;
		}
		this.status = ProgressStatus.FAILED;
		this.lastError = truncate(error);
		this.completedAt = now;
	}

	/**
	 * 관리자 수동 재개(설계 D5) — FAILED를 풀어 파이프라인에 되돌린다. 아웃박스 메시지 부활(reactivate)과
	 * 짝으로 불린다. FAILED가 아니면 no-op(COMPLETED는 재개 대상이 아니다 — 이미 전시가 있다).
	 */
	public void reopen(LocalDateTime now) {
		if (this.status != ProgressStatus.FAILED) {
			return;
		}
		this.status = ProgressStatus.ENRICHING;
		this.lastError = null;
		this.completedAt = null;
	}

	/** 상세 스텝이 아직 해소되지 않은 보강 대상인가(스텝 핸들러의 ① 판정). */
	public boolean needsDetail() {
		return !this.status.isTerminal() && this.detailResolvedAt == null;
	}

	/** 장르 스텝이 아직 해소되지 않은 보강 대상인가. */
	public boolean needsGenre() {
		return !this.status.isTerminal() && this.genreClassifiedAt == null;
	}

	private void markEnriching() {
		if (this.status == ProgressStatus.PENDING) {
			this.status = ProgressStatus.ENRICHING;
		}
	}

	private static String truncate(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
	}

	@PrePersist
	private void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	private void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
