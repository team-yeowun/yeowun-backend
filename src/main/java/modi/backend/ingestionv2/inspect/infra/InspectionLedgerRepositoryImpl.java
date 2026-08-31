package modi.backend.ingestionv2.inspect.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.inspect.domain.InspectionLedger;
import modi.backend.ingestionv2.inspect.domain.InspectionLedgerRepository;

/**
 * 원장 단면 어댑터.
 *
 * <ul>
 *   <li>네이티브 SQL - 다른 격벽의 엔티티 타입을 들이지 않기 위함</li>
 *   <li>세 원장을 내부 조인 (하나라도 없으면 단면이 만들어지지 않음)</li>
 *   <li>한 번의 조회로 끝냄 (판단 재료가 여러 번의 조회에 걸치지 않게 함)</li>
 *   <li>불리언 컬럼은 Boolean 과 Number 를 모두 받음 (커넥터가 TINYINT(1) 을 Boolean 으로 매핑)</li>
 * </ul>
 *
 * <p>이 파일이 수집 슬라이스 안에서 유일하게 다른 격벽의 테이블 이름을 아는 자리다.
 * 원장 열 이름 변경은 파괴적 변경이며 이 SQL 을 같은 변경 묶음에서 함께 고쳐야 한다.
 */
@Repository
@RequiredArgsConstructor
public class InspectionLedgerRepositoryImpl implements InspectionLedgerRepository {

	private static final String LEDGER_SQL = """
			SELECT l.title, l.start_date, l.end_date, l.area, l.gps_x, l.gps_y,
			       g.genre_keyword, p.absent, p.regular_opening_hours
			  FROM ingestion_culture_list_snapshot l
			  JOIN ingestion_genre_snapshot g ON g.vendor_key = l.vendor_key
			  JOIN ingestion_google_place_snapshot p ON p.vendor_key = l.vendor_key
			 WHERE l.vendor_key = :vendorKey
			""";

	private final EntityManager entityManager;

	@Override
	public Optional<InspectionLedger> findByVendorKey(String vendorKey) {
		List<?> rows = entityManager.createNativeQuery(LEDGER_SQL)
				.setParameter("vendorKey", vendorKey)
				.getResultList();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Object[] row = (Object[]) rows.get(0);
		return Optional.of(new InspectionLedger(
				text(row[0]), text(row[1]), text(row[2]), text(row[3]),
				text(row[4]), text(row[5]), text(row[6]),
				flag(row[7]), text(row[8])));
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	/** MySQL 커넥터는 tinyInt1isBit 기본값이 참이라 TINYINT(1) 을 Boolean 으로 돌려준다. 둘 다 받는다. */
	private static boolean flag(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		return value instanceof Number number && number.intValue() != 0;
	}
}
