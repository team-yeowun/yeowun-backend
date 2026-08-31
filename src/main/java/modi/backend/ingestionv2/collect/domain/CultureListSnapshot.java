package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;

/**
 * 목록 응답 원장.
 *
 * <ul>
 *   <li>행 1개 = 목록 응답에 담긴 전시 1건의 첫 관측</li>
 *   <li>벤더 응답 필드를 verbatim 적재 (날짜·좌표도 문자열 그대로, 타입 변환은 스테이징 몫)</li>
 *   <li>vendor_key UNIQUE = 첫 관측만 남는다는 성질의 물리 근거</li>
 * </ul>
 */
@Entity(name = "IngestionV2CultureListSnapshot")
@Table(
		name = "ingestion_culture_list_snapshot",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_ingestion_culture_list_snapshot_vendor_key",
				columnNames = "vendor_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CultureListSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, length = 100)
	private String vendorKey;

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

	@Column(name = "detail_url", length = 1000)
	private String detailUrl;

	@Column(name = "observed_at", nullable = false, updatable = false)
	private LocalDateTime observedAt;

	private CultureListSnapshot(CatalogItem item, LocalDateTime observedAt) {
		this.vendorKey = item.vendorKey();
		this.title = item.title();
		this.startDate = item.startDate();
		this.endDate = item.endDate();
		this.place = item.place();
		this.realmName = item.realmName();
		this.area = item.area();
		this.sigungu = item.sigungu();
		this.thumbnail = item.thumbnail();
		this.gpsX = item.gpsX();
		this.gpsY = item.gpsY();
		this.serviceName = item.serviceName();
		this.detailUrl = item.detailUrl();
		this.observedAt = observedAt;
	}

	/** 목록 응답 1건을 원장 행으로 옮긴다. 값 정제 없음. */
	public static CultureListSnapshot observe(CatalogItem item) {
		return new CultureListSnapshot(item, IngestionClock.now());
	}
}
