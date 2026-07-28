package modi.backend.domain.exhibition.catalog;

import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.domain.exhibition.hours.PlaceKey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.support.entity.BaseEntity;

/**
 * 전시장(도메인) — {@code exhibition_place} 매핑. <b>행 = 전시장 1곳</b>(UK {@code place_key} = 정규화 이름, ADR-07).
 * 한 전시장을 여러 전시가 공유한다(N:1, ADR-06) — 그래서 유료 영업시간 조회도 장소당 1회면 된다.
 *
 * <p>신원 필드(name/region/sigungu/gps)는 <b>목록(전시 생성 시점)</b>에서 온다 → 전시 생성과 동시에 확정되어
 * {@code exhibitions.exhibition_place_id NOT NULL}을 지탱한다(ADR-05). 보강 필드(address/phone/place_url)는 상세가
 * 도착해야 채워지므로 Optional이다({@link #enrichDetail}).
 *
 * <p>{@link Exhibition}과는 {@code exhibition_place_id}로 이어진다(자식→부모 실제 FK). 영업시간·구글 원본은 이 전시장에
 * 1:1로 정렬된다({@link PlaceHours}·{@code GooglePlaceSnapshot}가 {@code exhibition_place_id}로 참조).
 */
@Entity
@Table(name = "exhibition_place")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExhibitionPlace extends BaseEntity {

	/** 자연키 = 정규화한 전시장 이름({@link PlaceKey#of}). UK. */
	@Column(name = "place_key", nullable = false, length = 500)
	private String placeKey;

	/** 표시용 전시장 이름(목록 소스 원문). */
	@Column(name = "name", nullable = false, length = 200)
	private String name;

	/** 지역(전시가 아니라 전시장의 속성 — region 이동). CUSTOM 등록에서 미지정 가능해 nullable. */
	@Enumerated(EnumType.STRING)
	@Column(name = "region", length = 20)
	private ExhibitionRegion region;

	@Column(name = "sigungu", length = 50)
	private String sigungu;

	@Column(name = "gps_x")
	private Double gpsX;

	@Column(name = "gps_y")
	private Double gpsY;

	/** 보강 필드 — 상세 도착 시 채운다(전시장 상세 주소). */
	@Column(name = "address", length = 500)
	private String address;

	@Column(name = "phone", length = 100)
	private String phone;

	@Column(name = "place_url", length = 2048)
	private String placeUrl;

	/** 조회 전용 센티넬 표식 — 영속 컬럼이 아니다. */
	@Transient
	private boolean sentinel;

	private ExhibitionPlace(String name, ExhibitionRegion region, String sigungu, Double gpsX, Double gpsY) {
		this.placeKey = PlaceKey.of(name);
		this.name = normalizedName(name);
		this.region = region;
		this.sigungu = sigungu;
		this.gpsX = gpsX;
		this.gpsY = gpsY;
	}

	/**
	 * <b>장소 미상 센티넬</b>(조회 전용). 전시장은 {@code exhibition_place_id NOT NULL}이라 실제로는 늘 존재하지만,
	 * 조회가 행을 못 찾는 경계(데이터 파손 등)에서 호출부가 <b>필드마다 null을 확인하지 않도록</b> 빈 전시장을 준다.
	 *
	 * <p>이름 없는 전시장을 {@link PlaceKey#UNKNOWN}으로 수렴시키는 기존 규칙의 객체판이다.
	 * <b>저장할 수 없다</b> — {@link #guard()}가 영속화 시점에 막는다.
	 */
	public static ExhibitionPlace unknown() {
		ExhibitionPlace place = new ExhibitionPlace();
		place.sentinel = true;
		return place;
	}

	/** 목록 소스로 전시장을 신설한다(resolve-or-create의 create). 자연키는 정규화 이름이라 같은 이름은 하나로 수렴한다. */
	public static ExhibitionPlace createFromList(String name, ExhibitionRegion region, String sigungu,
			Double gpsX, Double gpsY) {
		return new ExhibitionPlace(name, region, sigungu, gpsX, gpsY);
	}

	/**
	 * 상세 보강 — 주소/전화/홈페이지를 채운다. 이미 있던 값은 새 값이 있을 때만 갱신한다(상세 재수집이 기존 값을 null로
	 * 지우지 않게). 신원 필드(name/region/gps)는 건드리지 않는다 — 그건 목록이 확정한다.
	 */
	public void enrichDetail(String address, String phone, String placeUrl) {
		if (address != null && !address.isBlank()) {
			this.address = address;
		}
		if (phone != null && !phone.isBlank()) {
			this.phone = phone;
		}
		if (placeUrl != null && !placeUrl.isBlank()) {
			this.placeUrl = placeUrl;
		}
	}

	/** 신원 필드 보강 — 나중에 목록 소스로 다시 만났을 때 비어 있던 값만 채운다(기존 값 보존). */
	public void enrichIdentity(ExhibitionRegion region, String sigungu, Double gpsX, Double gpsY) {
		if (this.region == null && region != null) {
			this.region = region;
		}
		if (this.sigungu == null && sigungu != null) {
			this.sigungu = sigungu;
		}
		if (this.gpsX == null && gpsX != null) {
			this.gpsX = gpsX;
		}
		if (this.gpsY == null && gpsY != null) {
			this.gpsY = gpsY;
		}
	}

	private static String normalizedName(String name) {
		String key = PlaceKey.of(name);
		return key;
	}
	/** 센티넬은 조회용이라 저장하면 유령 행이 된다 — 영속화 시점에 막는다. */
	@Override
	protected void guard() {
		if (sentinel) {
			throw new IllegalStateException("장소 미상 센티넬은 저장할 수 없습니다(조회 전용).");
		}
	}

}
