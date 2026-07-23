package modi.backend.ingestion.domain.snapshot;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;

/**
 * 한눈에보는문화정보 목록(realm2) 응답 스냅샷(벤더층) — {@code culture_list_snapshot} 매핑.
 *
 * <p><b>행 = 목록 응답 중 전시 1건의 스냅샷</b>이다(페이지 단위 ❌). UK({@code external_id})라 재수집이 no-op가
 * 되어 크기가 원천(280건 수준)에 수렴한다.
 *
 * <p><b>응답 구조 필드 적재(ADR-13, ADR-01 폐기)</b>: raw payload 문자열 대신 realm2 응답 아이템의 필드를
 * 컬럼으로 적재한다. 값의 출처는 {@link CatalogExhibitionData} — 별도의 원문 verbatim 어휘를 두지 않는다
 * (같은 12필드를 두 벌 나르는 중복이었다). 날짜·좌표는 정제 타입이라 <b>문자열로 치환해</b> 담는다(컬럼은
 * VARCHAR 유지 — 스키마 변경 없음). 대가: 원천이 파싱 불가한 값을 준 행에서는 그 원문이 남지 않는다.
 *
 * <p>원천이 값을 정정했는지는 <b>적재 필드를 직접 비교</b>해 행 단위로 감지한다 — 별도 해시 컬럼을 두지 않는다
 * (payload를 통째로 들고 있던 시절의 잔재였고, 필드가 컬럼이 된 지금은 비교가 곧 판정이다).
 * {@code last_seen_at}은 이번 동기화에도 원천에 있었는지 — 사라진 항목 판별용.
 *
 * <p>{@code exhibitions}와는 ID·키 참조도 두지 않는다 — 벤더층은 도메인이 적재하지 않은 항목(기간 불량 스킵 등)도
 * 기록한다. 감사 컬럼 대신 {@code first_seen_at}·{@code last_seen_at}이 도메인 언어로 그 역할을 한다.
 */
@Entity
@Table(name = "culture_list_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CultureListSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_id", nullable = false, length = 100)
	private String externalId;

	// ── realm2 응답 아이템 필드(CatalogExhibitionData에서 옮겨 담는다 — 정제 타입은 문자열로 치환) ──
	@Column(name = "title", length = 500)
	private String title;

	@Column(name = "start_date", length = 20)
	private String startDate;

	@Column(name = "end_date", length = 20)
	private String endDate;

	@Column(name = "place", length = 300)
	private String place;

	@Column(name = "realm_name", length = 100)
	private String realmName;

	@Column(name = "area", length = 100)
	private String area;

	@Column(name = "sigungu", length = 100)
	private String sigungu;

	@Column(name = "thumbnail", length = 1000)
	private String thumbnail;

	@Column(name = "gps_x", length = 50)
	private String gpsX;

	@Column(name = "gps_y", length = 50)
	private String gpsY;

	@Column(name = "service_name", length = 200)
	private String serviceName;

	@Column(name = "first_seen_at")
	private LocalDateTime firstSeenAt;

	@Column(name = "last_seen_at")
	private LocalDateTime lastSeenAt;

	private CultureListSnapshot(CatalogExhibitionData data, LocalDateTime now) {
		this.externalId = data.externalId();
		copyFields(data);
		this.firstSeenAt = now;
		this.lastSeenAt = now;
	}

	/** 원천에서 처음 본 아이템. */
	public static CultureListSnapshot first(CatalogExhibitionData data, LocalDateTime now) {
		return new CultureListSnapshot(data, now);
	}

	/**
	 * 이번 동기화에도 원천에 있었다 — {@code last_seen_at}은 값이 그대로여도 항상 갱신한다("아직 살아 있다"는 사실 자체가 정보다).
	 * 필드는 <b>값이 달라졌을 때만</b> 덮는다 — 원천이 값을 정정한 경우다(같은 값으로 매일 덮으면 "언제 바뀌었나"를 잃는다).
	 * 레거시 행(V39 이전 — 구조화 필드 null)은 첫 재동기화에서 "변경됨"으로 판정돼 필드가 자동 채워진다.
	 */
	public void seenAgain(CatalogExhibitionData data, LocalDateTime now) {
		this.lastSeenAt = now;
		if (isChangedFrom(data)) {
			copyFields(data);
		}
	}

	/** 원천이 이 아이템의 값을 정정했는가 — 적재 필드를 그대로 비교한다. */
	private boolean isChangedFrom(CatalogExhibitionData data) {
		return !Objects.equals(this.title, data.title())
				|| !Objects.equals(this.startDate, text(data.startDate()))
				|| !Objects.equals(this.endDate, text(data.endDate()))
				|| !Objects.equals(this.place, data.place())
				|| !Objects.equals(this.realmName, data.realmName())
				|| !Objects.equals(this.area, data.areaText())
				|| !Objects.equals(this.sigungu, data.sigungu())
				|| !Objects.equals(this.thumbnail, data.posterUrl())
				|| !Objects.equals(this.gpsX, text(data.gpsX()))
				|| !Objects.equals(this.gpsY, text(data.gpsY()))
				|| !Objects.equals(this.serviceName, data.serviceName());
	}

	private void copyFields(CatalogExhibitionData data) {
		this.title = data.title();
		this.startDate = text(data.startDate());
		this.endDate = text(data.endDate());
		this.place = data.place();
		this.realmName = data.realmName();
		this.area = data.areaText();
		this.sigungu = data.sigungu();
		this.thumbnail = data.posterUrl();
		this.gpsX = text(data.gpsX());
		this.gpsY = text(data.gpsY());
		this.serviceName = data.serviceName();
	}

	/** 정제 타입을 컬럼(VARCHAR)에 담을 문자열로 — 결측은 null 그대로 둔다("" 로 만들면 결측과 빈 값이 섞인다). */
	private static String text(Object value) {
		return value == null ? null : value.toString();
	}
}
