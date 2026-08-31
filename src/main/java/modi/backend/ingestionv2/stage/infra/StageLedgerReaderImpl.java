package modi.backend.ingestionv2.stage.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.stage.domain.StageLedger;
import modi.backend.ingestionv2.stage.domain.StageLedgerReader;

/**
 * 원장 읽기 어댑터.
 *
 * <ul>
 *   <li>앞 도메인의 엔티티를 참조하지 않기 위해 SQL 로 직접 조회</li>
 *   <li>조립에 쓰는 열만 선택해 앞 도메인의 열 추가에 영향받지 않음</li>
 *   <li>원장은 원천 키당 최대 1행이라 단건 조회로 충분</li>
 * </ul>
 *
 * <p><b>이 SQL 은 계약이다.</b> 원장 열의 이름과 타입은 소유 도메인의 사적인 것이 아니라 스테이징이
 * 읽는 공개 계약이며, 이름을 바꾸는 마이그레이션은 이 파일과 같은 변경 묶음에 있어야 한다.
 * 함께 바뀌지 않으면 컴파일은 통과하고 첫 승격에서 {@code Unknown column} 으로 통째로 터진다.
 */
@Repository
@RequiredArgsConstructor
public class StageLedgerReaderImpl implements StageLedgerReader {

	private static final String LISTING_SQL = """
			select vendor_key, title, start_date, end_date, place, realm_name, area, sigungu,
			       thumbnail, gps_x, gps_y, service_name, detail_url
			  from ingestion_culture_list_snapshot
			 where vendor_key = ?
			""";

	private static final String DETAIL_SQL = """
			select absent, price, contents, url, phone, img_url, place
			  from ingestion_culture_detail_snapshot
			 where vendor_key = ?
			""";

	private static final String GENRE_SQL = """
			select genre_keyword, vendor, model
			  from ingestion_genre_snapshot
			 where vendor_key = ?
			""";

	private static final String PLACE_SQL = """
			select absent, regular_opening_hours
			  from ingestion_google_place_snapshot
			 where vendor_key = ?
			""";

	private static final RowMapper<StageLedger.Listing> LISTING_MAPPER = (row, index) -> new StageLedger.Listing(
			row.getString("vendor_key"),
			row.getString("title"),
			row.getString("start_date"),
			row.getString("end_date"),
			row.getString("place"),
			row.getString("realm_name"),
			row.getString("area"),
			row.getString("sigungu"),
			row.getString("thumbnail"),
			row.getString("gps_x"),
			row.getString("gps_y"),
			row.getString("service_name"),
			row.getString("detail_url"));

	private static final RowMapper<StageLedger.Detail> DETAIL_MAPPER = (row, index) -> new StageLedger.Detail(
			row.getBoolean("absent"),
			row.getString("price"),
			row.getString("contents"),
			row.getString("url"),
			row.getString("phone"),
			row.getString("img_url"),
			row.getString("place"));

	private static final RowMapper<StageLedger.Genre> GENRE_MAPPER = (row, index) -> new StageLedger.Genre(
			row.getString("genre_keyword"),
			row.getString("vendor"),
			row.getString("model"));

	private static final RowMapper<StageLedger.Place> PLACE_MAPPER = (row, index) -> new StageLedger.Place(
			row.getBoolean("absent"),
			row.getString("regular_opening_hours"));

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<StageLedger.Listing> readListing(String vendorKey) {
		return single(jdbcTemplate.query(LISTING_SQL, LISTING_MAPPER, vendorKey));
	}

	@Override
	public Optional<StageLedger.Detail> readDetail(String vendorKey) {
		return single(jdbcTemplate.query(DETAIL_SQL, DETAIL_MAPPER, vendorKey));
	}

	@Override
	public Optional<StageLedger.Genre> readGenre(String vendorKey) {
		return single(jdbcTemplate.query(GENRE_SQL, GENRE_MAPPER, vendorKey));
	}

	@Override
	public Optional<StageLedger.Place> readPlace(String vendorKey) {
		return single(jdbcTemplate.query(PLACE_SQL, PLACE_MAPPER, vendorKey));
	}

	private static <T> Optional<T> single(List<T> rows) {
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}
}
