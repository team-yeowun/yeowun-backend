package modi.backend.ingestion.domain.snapshot;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestion.domain.data.PlaceHoursResult;

/**
 * 구글 Places(New) 응답 스냅샷(벤더층) — {@code google_place_snapshot} 매핑, 전시장({@code exhibition_place})당 1행.
 *
 * <p><b>응답 구조 필드 적재(ADR-13, ADR-01 폐기)</b>: raw JSON 대신 응답 구조 필드(place_id·displayName·
 * formattedAddress)로 적재하고, 깊은 중첩(영업시간 periods·weekdayDescriptions)은 <b>구조 보존 JSON 컬럼</b>으로
 * 남긴다 — "응답 구조에 맞게"의 실용 해석. 정준층({@code place_hours})은 이와 별도로 파싱·포맷된 표시값을 가진다.
 */
@Entity
@Table(name = "google_place_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GooglePlaceSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "exhibition_place_id", nullable = false)
	private Long exhibitionPlaceId;

	/** 구글 Place 리소스 id — 재조회·중복 판정의 벤더 측 키. */
	@Column(name = "place_id", length = 200)
	private String placeId;

	@Column(name = "display_name", length = 300)
	private String displayName;

	@Column(name = "formatted_address", length = 500)
	private String formattedAddress;

	/** regularOpeningHours(periods·weekdayDescriptions) 구조 보존 JSON — 깊은 중첩의 실용 적재. */
	@Column(name = "regular_opening_hours", columnDefinition = "json")
	private String regularOpeningHours;

	@Column(name = "fetched_at")
	private LocalDateTime fetchedAt;

	private GooglePlaceSnapshot(Long exhibitionPlaceId, PlaceHoursResult item, LocalDateTime fetchedAt) {
		this.exhibitionPlaceId = exhibitionPlaceId;
		copyFields(item);
		this.fetchedAt = fetchedAt;
	}

	/** 이 전시장의 첫 구글 응답 스냅샷. */
	public static GooglePlaceSnapshot first(Long exhibitionPlaceId, PlaceHoursResult item, LocalDateTime fetchedAt) {
		return new GooglePlaceSnapshot(exhibitionPlaceId, item, fetchedAt);
	}

	/** 재검증으로 받은 최신 스냅샷으로 갱신한다(전시장당 1행 유지). */
	public void refresh(PlaceHoursResult item, LocalDateTime fetchedAt) {
		copyFields(item);
		this.fetchedAt = fetchedAt;
	}

	private void copyFields(PlaceHoursResult item) {
		if (item == null) {
			return;
		}
		this.placeId = item.placeId();
		this.displayName = item.displayNameText();
		this.formattedAddress = item.formattedAddress();
		this.regularOpeningHours = item.regularOpeningHoursJson();
	}
}
