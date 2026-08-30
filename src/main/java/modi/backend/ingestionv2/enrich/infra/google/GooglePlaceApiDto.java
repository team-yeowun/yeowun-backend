package modi.backend.ingestionv2.enrich.infra.google;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import modi.backend.ingestionv2.enrich.domain.hours.PlaceData;
import tools.jackson.databind.JsonNode;

/**
 * 구글 Places(New) places:searchText 요청·응답 바인딩.
 *
 * <ul>
 *   <li>응답 record가 자기를 원장 어휘로 옮김 - 후보 랭킹 1순위 채택</li>
 *   <li>영업시간은 깊은 중첩이라 JsonNode로 받아 원문 JSON 그대로 보존 - 요일·시각 해석은 어셈블 몫</li>
 *   <li>FieldMask로 받을 필드만 요청하지만 응답엔 관심 밖 필드가 섞여 오므로 관대한 파싱</li>
 * </ul>
 */
public final class GooglePlaceApiDto {

	private GooglePlaceApiDto() {
	}

	/** Text Search 요청 본문. API 키·FieldMask는 헤더로 나가므로 여기 없음. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SearchTextRequest(String textQuery, String languageCode, String regionCode) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SearchTextResponse(List<Place> places) {

		/** 결측 필드가 null로 와도 이후 로직은 항상 리스트를 다룸(null 분기 제거). */
		public SearchTextResponse {
			places = places == null ? List.of() : places;
		}

		/** 랭킹 1순위 후보 - 질의 정확도(장소명+주소)에 기대는 신뢰 정책. */
		public Optional<Place> firstPlace() {
			return places.isEmpty() ? Optional.empty() : Optional.of(places.get(0));
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Place(String id, DisplayName displayName, String formattedAddress, JsonNode regularOpeningHours) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record DisplayName(String text, String languageCode) {
		}

		/** 원장 어휘로 옮김 - 영업시간은 구조 보존 JSON 문자열, 나머지는 원문 그대로. */
		public PlaceData toPlaceData() {
			boolean hoursAbsent = regularOpeningHours == null || regularOpeningHours.isNull();
			return new PlaceData(id, displayName == null ? null : displayName.text(), formattedAddress,
					hoursAbsent ? null : regularOpeningHours.toString(), false);
		}
	}
}
