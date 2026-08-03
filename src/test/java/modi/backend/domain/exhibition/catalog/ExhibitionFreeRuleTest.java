package modi.backend.domain.exhibition.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 무료 판정 규칙({@link Exhibition#isFreePrice(String)}) — V49가 SQL {@code LIKE '%무료%'}에서
 * 도메인 코드로 옮겨온 규칙. <b>이 테스트가 가능해진 것이 그 이동의 진짜 이득이다.</b>
 *
 * <p>세 가지를 고정한다:
 * <ol>
 *   <li>규칙 자체(문서에 적힌 예시들)</li>
 *   <li>옛 규칙 대비 <b>무엇이 빠지는가</b> — 의도된 동작 변화라 값으로 못 박는다</li>
 *   <li><b>SQL 백필과의 일치</b> — V49의 WHERE 절이 실데이터 가격 57종에 내린 판정을 픽스처로 떠서 대조한다.
 *       어긋나면 기존 행(SQL이 굳힌 값)과 신규 행(이 코드가 굳힌 값)이 다른 규칙을 타게 된다.</li>
 * </ol>
 */
class ExhibitionFreeRuleTest {

	/** 픽스처 생성 명령(실데이터 DB에서 V49의 WHERE 절을 그대로 실행). 규칙을 바꾸면 이걸 다시 돌린다. */
	private static final String FIXTURE = "/fixture/exhibition-price-free-sql-verdict.tsv";

	@ParameterizedTest(name = "[{index}] {0} → {1}")
	@CsvSource(delimiter = '|', value = {
			// ── 무료로 남는 것 ────────────────────────────────────────────
			"무료|true",
			"무료관람|true",
			"무료입장|true",
			"무료 *단체관람은 홈페이지 신청 필수|true",
			"무료 / ※ 경복궁 관람료 별도|true",
			"0원|true",
			// ── 유료 판정(옛 규칙이 무료로 오탐하던 것들) ──────────────────
			"성인 2000원 / 청소년 1000원 / 노인 및 유아 무료|false",
			"현장등록 5000원 (사전등록시 무료입장)|false",
			"3000원 (*카페 이용시 무료)|false",
			// ── 원래도 유료 ──────────────────────────────────────────────
			"미정|false",
			"해당사항 없음|false",
			"20000원|false",
			"유료 500~1000원|false",
	})
	@DisplayName("규칙: 0이 아닌 금액이 있으면 유료, 없고 '무료'가 있으면 무료")
	void 규칙(String price, boolean expected) {
		assertThat(Exhibition.isFreePrice(price)).isEqualTo(expected);
	}

	@Test
	@DisplayName("가격 미상(null·공백)은 무료가 아니다")
	void 가격_미상은_무료가_아니다() {
		assertThat(Exhibition.isFreePrice(null)).isFalse();
		assertThat(Exhibition.isFreePrice("")).isFalse();
		assertThat(Exhibition.isFreePrice("   ")).isFalse();
	}

	@Test
	@DisplayName("옛 규칙이 무료로 잡던 부분무료 표기가 이제는 유료다 (의도된 동작 변화)")
	void 옛규칙과_갈리는_지점() {
		// 옛 규칙 = price.contains("무료") || price.equals("0원").
		// 아래 문자열들은 전부 "무료"를 포함하므로 옛 규칙에서는 무료였다.
		List<String> 옛규칙이_무료로_잡던_유료 = List.of(
				"성인 2,000원 / 청소년 1,000원 / 어린이 500원 / 노인 및 유아 무료",
				"성인 23,000원 / 청소년 19,000원 / 어린이 16,000원 / 36개월 미만 무료",
				"짜장면박물관 입장권 성인 1,000원, 청소년700원, 군경500원, 어린이 및 만 65세이상 무료",
				"성인: 1000원(울산시민 500원) / 어린이, 청소년, 경로: 무료");

		for (String price : 옛규칙이_무료로_잡던_유료) {
			assertThat(price).contains("무료");                       // 옛 규칙이라면 무료였다
			assertThat(Exhibition.isFreePrice(price)).isFalse();     // 새 규칙은 유료로 본다
		}
	}

	@Test
	@DisplayName("SQL 백필(V49)과 도메인 규칙이 실데이터 가격 57종에서 완전히 같은 판정을 낸다")
	void SQL_백필과_일치한다() throws IOException {
		List<String[]> rows = loadFixture();
		assertThat(rows).hasSize(57);        // 픽스처가 비거나 잘리면 이 테스트는 공허해진다

		List<String> mismatches = new ArrayList<>();
		int free = 0;
		for (String[] row : rows) {
			boolean sqlVerdict = "1".equals(row[0]);
			boolean domainVerdict = Exhibition.isFreePrice(row[1]);
			if (sqlVerdict != domainVerdict) {
				mismatches.add("SQL=" + sqlVerdict + " domain=" + domainVerdict + " price=" + row[1]);
			}
			if (sqlVerdict) {
				free++;
			}
		}

		assertThat(mismatches).as("SQL 백필과 도메인 규칙이 갈리는 가격").isEmpty();
		assertThat(free).as("57종 중 무료로 판정되는 가격 종류").isEqualTo(5);
	}

	/** 열 구분자는 {@code ~|~} — 가격 원문에 탭·파이프가 섞여 있어도 안전한 조합이다(실데이터 0건 확인). */
	private static List<String[]> loadFixture() throws IOException {
		try (InputStream in = ExhibitionFreeRuleTest.class.getResourceAsStream(FIXTURE)) {
			assertThat(in).as("픽스처 %s", FIXTURE).isNotNull();
			List<String[]> rows = new ArrayList<>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				rows.add(line.split("~\\|~", 2));
			}
			return rows;
		}
	}
}
