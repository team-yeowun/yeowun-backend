package modi.backend.ingestionv2.enrich.domain.detail;

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
 * 문화포털 상세 응답 원장.
 *
 * <ul>
 *   <li>벤더 응답을 정제 없이 그대로 적재. 날짜·좌표도 원문 문자열</li>
 *   <li>원천에 상세가 없는 경우도 absent 행으로 남김. 값 부재와 미조회를 구분</li>
 *   <li>보강 격벽의 유일한 입력 원천. 장르와 개장 시간이 이 행을 읽는다</li>
 * </ul>
 */
@Entity(name = "IngestionV2CultureDetailSnapshot")
@Table(name = "ingestion_culture_detail_snapshot",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_ingestion_culture_detail_snapshot_vendor_key",
				columnNames = "vendor_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CultureDetailSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, unique = true, length = 100)
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

	@Column(name = "gps_x", length = 50)
	private String gpsX;

	@Column(name = "gps_y", length = 50)
	private String gpsY;

	@Column(name = "price", columnDefinition = "text")
	private String price;

	@Column(name = "contents", columnDefinition = "mediumtext")
	private String contents;

	@Column(name = "url", length = 1000)
	private String url;

	@Column(name = "phone", length = 100)
	private String phone;

	@Column(name = "img_url", length = 1000)
	private String imgUrl;

	@Column(name = "absent", nullable = false)
	private boolean absent;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private CultureDetailSnapshot(String vendorKey, DetailData data) {
		this.vendorKey = vendorKey;
		this.title = data.title();
		this.startDate = data.startDate();
		this.endDate = data.endDate();
		this.place = data.place();
		this.realmName = data.realmName();
		this.area = data.area();
		this.sigungu = data.sigungu();
		this.gpsX = data.gpsX();
		this.gpsY = data.gpsY();
		this.price = data.price();
		this.contents = data.contents();
		this.url = data.url();
		this.phone = data.phone();
		this.imgUrl = data.imgUrl();
		this.absent = data.absent();
		this.createdAt = IngestionClock.now();
	}

	public static CultureDetailSnapshot create(String vendorKey, DetailData data) {
		return new CultureDetailSnapshot(vendorKey, data);
	}

	/** 장르 분류 입력이 될 만한 값이 있는지. */
	public boolean hasClassifiableText() {
		return isFilled(title) || isFilled(contents);
	}

	/** 개장 시간 조회 입력이 될 만한 값이 있는지. */
	public boolean hasSearchablePlace() {
		return isFilled(place);
	}

	private static boolean isFilled(String value) {
		return value != null && !value.isBlank();
	}
}
