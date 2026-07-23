package modi.backend.ingestion.domain.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestion.domain.data.CultureDetailPayload;

/**
 * 한눈에보는문화정보 상세(detail2) 응답 스냅샷(벤더층) — {@code culture_detail_snapshot} 매핑.
 *
 * <p><b>응답 구조 필드 적재(ADR-13, ADR-01 폐기)</b>: raw payload 문자열 대신 detail2 응답 아이템의 필드를
 * <b>원문 verbatim</b> 컬럼으로 적재한다. <b>어댑터가 받은 응답({@link Item})을 그대로 옮긴다</b> —
 * 그래서 이 엔티티는 infra에 있다(2026-07-21): 수집의 책임이 "외부 응답을 그대로 저장"이라 도메인 어휘를 거칠 이유가 없고,
 * 도메인 값으로 옮겨 담으면 {@code contents}의 워드프레스 HTML 원문이 평문 추출 이후 값이 되어 재추출 원료를 잃는다. 특히 {@code contents}는 워드프레스 블록/HTML 원문이라, 평문 추출
 * 규칙이 바뀌면 여기서 재추출한다(재호출 없는 재가공 소스 — 필드 단위로 승계). 멱등 upsert(UK external_id, 1행).
 * 레거시 행(V39 이전)은 필드 null 잔존을 허용한다 — 상세는 대상당 1회 조회 기록층이라 재적재 트리거가 없다.
 */
@Entity
@Table(name = "culture_detail_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CultureDetailSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_id", nullable = false, length = 100)
	private String externalId;

	// ── detail2 응답 아이템 필드(원문 verbatim — 평문 추출·디코드는 도메인 몫) ──
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

	@Column(name = "gps_x", length = 50)
	private String gpsX;

	@Column(name = "gps_y", length = 50)
	private String gpsY;

	@Column(name = "price", columnDefinition = "text")
	private String price;

	/** 워드프레스 블록/HTML 원문 — 평문 추출 규칙 변경 시 재가공 소스. */
	@Column(name = "contents", columnDefinition = "mediumtext")
	private String contents;

	@Column(name = "url", length = 1000)
	private String url;

	@Column(name = "phone", length = 100)
	private String phone;

	@Column(name = "img_url", length = 1000)
	private String imgUrl;

	@Column(name = "place_url", length = 1000)
	private String placeUrl;

	@Column(name = "place_addr", length = 500)
	private String placeAddr;

	@Column(name = "place_seq", length = 50)
	private String placeSeq;

	private CultureDetailSnapshot(String externalId, CultureDetailPayload item) {
		this.externalId = externalId;
		copyFields(item);
	}

	/** 원천이 상세를 준 첫 응답의 스냅샷을 보관한다. */
	public static CultureDetailSnapshot first(String externalId, CultureDetailPayload item) {
		return new CultureDetailSnapshot(externalId, item);
	}

	/** 재조회로 받은 최신 스냅샷으로 갱신한다(멱등 upsert — 같은 external_id는 항상 1행). */
	public void refresh(CultureDetailPayload item) {
		copyFields(item);
	}

	private void copyFields(CultureDetailPayload item) {
		if (item == null) {
			return;
		}
		this.title = item.title();
		this.startDate = item.startDate();
		this.endDate = item.endDate();
		this.place = item.place();
		this.realmName = item.realmName();
		this.area = item.area();
		this.sigungu = item.sigungu();
		this.gpsX = item.gpsX();
		this.gpsY = item.gpsY();
		this.price = item.price();
		this.contents = item.contents1();
		this.url = item.url();
		this.phone = item.phone();
		this.imgUrl = item.imgUrl();
		this.placeUrl = item.placeUrl();
		this.placeAddr = item.placeAddr();
		this.placeSeq = item.placeSeq();
	}
}
