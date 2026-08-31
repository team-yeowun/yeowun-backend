package modi.backend.ingestionv2.stage;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.stage.domain.StageFacade;

/**
 * 스테이징 통합 테스트 지지대.
 *
 * <ul>
 *   <li>원장 씨앗은 앞 도메인의 클래스를 부르지 않고 SQL 로 직접 심음(경계 규칙을 테스트도 지킴)</li>
 *   <li>관측은 JPA 가 아니라 JdbcTemplate 으로 수행 - 커밋된 행만 보고 영속성 컨텍스트 잔상을 배제</li>
 *   <li>전시장 이름을 원천 키에서 파생 - 코어 전시장은 이름으로 수렴하므로 고유해야 함</li>
 * </ul>
 *
 * <p>원장을 SQL 로 심는 이유가 하나 더 있다. 어댑터의 조회 SQL 과 씨앗의 열 이름이 같으므로,
 * 둘 중 하나가 실제 스키마와 어긋나면 씨앗 삽입이 첫 실행에서 곧바로 실패해 어긋남이 드러난다.
 * 컴파일러가 못 잡는 스키마 결합을 대신 잡아 주는 유일한 자리다.
 */
abstract class StageTestSupport extends IngestionTestSupport {

	@Autowired protected StageFacade stageFacade;

	/** 이 테스트에서만 쓰는 전시장 이름. 정규화가 공백만 건드리므로 접미사가 그대로 살아남는다. */
	protected String placeName;

	@BeforeEach
	void assignPlaceName() {
		placeName = "여운 미술관 " + vendorKey;
	}

	/** 점검을 통과한 전시가 갖추고 있을 원장 네 종을 심는다. */
	protected void seedReadyLedger(String key) {
		seedListing(key, placeName, "2026-08-01", "2026-12-31");
		seedGenre(key, "회화", "GEMINI");
		seedDetail(key, false);
		seedGooglePlace(key, "{\"weekdayDescriptions\":[\"월요일: 휴관\",\"화요일: 10:00~18:00\"]}", false);
	}

	protected void seedListing(String key, String place, String startDate, String endDate) {
		seedListing(key, place, startDate, endDate, "126.97", "37.57");
	}

	protected void seedListing(String key, String place, String startDate, String endDate, String gpsX, String gpsY) {
		jdbcTemplate.update("""
				insert into ingestion_culture_list_snapshot
				  (vendor_key, title, start_date, end_date, place, realm_name, area, sigungu,
				   thumbnail, gps_x, gps_y, service_name, detail_url, observed_at)
				values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
				""", key, "여운 기획전", startDate, endDate, place, "미술", "서울", "종로구",
				"https://img.example/thumb.jpg", gpsX, gpsY, "전시", "https://exh.example/1001",
				LocalDateTime.now());
	}

	/** 열 이름은 보강이 소유한 스키마 그대로다. 어긋나면 이 삽입이 첫 실행에서 실패해 어긋남이 드러난다. */
	protected void seedGenre(String key, String keyword, String vendor) {
		jdbcTemplate.update("""
				insert into ingestion_genre_snapshot
				  (vendor_key, genre_keyword, vendor, model, created_at)
				values (?,?,?,?,?)
				""", key, keyword, vendor, "test-model", LocalDateTime.now());
	}

	protected void seedDetail(String key, boolean absent) {
		jdbcTemplate.update("""
				insert into ingestion_culture_detail_snapshot
				  (vendor_key, place, price, contents, url, phone, img_url, absent, created_at)
				values (?,?,?,?,?,?,?,?,?)
				""", key, "여운 미술관 본관", "무료", "전시 설명 본문", "https://exh.example/detail",
				"02-000-0000", "https://img.example/detail.jpg", absent, LocalDateTime.now());
	}

	protected void seedGooglePlace(String key, String openingHoursJson, boolean absent) {
		jdbcTemplate.update("""
				insert into ingestion_google_place_snapshot
				  (vendor_key, vendor, place_id, regular_opening_hours, absent, created_at)
				values (?,?,?,?,?,?)
				""", key, "GOOGLE", "place-" + key, openingHoursJson, absent, LocalDateTime.now());
	}

	/** 코어 전시는 원천 키로만 센다. 전체 건수로 세면 다른 스위트가 만든 행이 섞인다. */
	protected int coreExhibitionCount(String key) {
		return jdbcTemplate.queryForObject("select count(*) from exhibitions where external_id = ?",
				Integer.class, key);
	}

	/** 코어 전시장도 자연 키로만 센다. */
	protected int corePlaceCount(String place) {
		return jdbcTemplate.queryForObject("select count(*) from exhibition_place where place_key = ?",
				Integer.class, PlaceKey.of(place));
	}

	protected int corePlaceHoursCount(String place) {
		return jdbcTemplate.queryForObject("""
				select count(*) from place_hours h
				  join exhibition_place p on p.id = h.exhibition_place_id
				 where p.place_key = ?
				""", Integer.class, PlaceKey.of(place));
	}

	protected Map<String, Object> coreExhibitionRow(String key) {
		return jdbcTemplate.queryForMap("select * from exhibitions where external_id = ?", key);
	}

	protected Map<String, Object> corePlaceHoursRow(String place) {
		return jdbcTemplate.queryForMap("""
				select h.* from place_hours h
				  join exhibition_place p on p.id = h.exhibition_place_id
				 where p.place_key = ?
				""", PlaceKey.of(place));
	}

	protected Map<String, Object> stagingRow(String key) {
		return jdbcTemplate.queryForMap("select * from ingestion_staging where vendor_key = ?", key);
	}
}
