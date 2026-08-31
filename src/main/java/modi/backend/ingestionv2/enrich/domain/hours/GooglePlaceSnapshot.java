package modi.backend.ingestionv2.enrich.domain.hours;

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
 * 구글 Places 응답 원장.
 *
 * <ul>
 *   <li>영업시간만 깊은 중첩이라 구조 보존 JSON 컬럼</li>
 *   <li>장소를 찾지 못한 경우도 absent 행으로 남김. 조회했다는 사실이 완비의 근거</li>
 *   <li>vendor는 당분간 값이 하나지만 지도 공급자 교체 여지를 위해 둠</li>
 * </ul>
 */
@Entity(name = "IngestionV2GooglePlaceSnapshot")
@Table(name = "ingestion_google_place_snapshot",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_ingestion_google_place_snapshot_vendor_key",
				columnNames = "vendor_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GooglePlaceSnapshot {

	public static final String VENDOR = "GOOGLE";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, unique = true, length = 100)
	private String vendorKey;

	@Column(name = "vendor", nullable = false, length = 20)
	private String vendor;

	@Column(name = "place_id", length = 200)
	private String placeId;

	@Column(name = "display_name", length = 300)
	private String displayName;

	@Column(name = "formatted_address", length = 500)
	private String formattedAddress;

	@Column(name = "regular_opening_hours", columnDefinition = "json")
	private String regularOpeningHours;

	@Column(name = "absent", nullable = false)
	private boolean absent;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private GooglePlaceSnapshot(String vendorKey, PlaceData data) {
		this.vendorKey = vendorKey;
		this.vendor = VENDOR;
		this.placeId = data.placeId();
		this.displayName = data.displayName();
		this.formattedAddress = data.formattedAddress();
		this.regularOpeningHours = data.regularOpeningHours();
		this.absent = data.absent();
		this.createdAt = IngestionClock.now();
	}

	public static GooglePlaceSnapshot create(String vendorKey, PlaceData data) {
		return new GooglePlaceSnapshot(vendorKey, data);
	}
}
