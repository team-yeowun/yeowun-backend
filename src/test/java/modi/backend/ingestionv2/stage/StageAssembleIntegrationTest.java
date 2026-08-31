package modi.backend.ingestionv2.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.ingestionv2.stage.domain.StageErrorCode;
import modi.backend.support.error.CoreException;

@DisplayName("어셈블")
class StageAssembleIntegrationTest extends StageTestSupport {

	@Test
	@DisplayName("ST-D1 점검을 통과한 값이 코어 타입으로 복원된다")
	void 점검을_통과한_값이_코어_타입으로_복원된다() {
		// given
		seedReadyLedger(vendorKey);

		// when
		stageFacade.stage(vendorKey);

		// then 원장의 값이 코어 전시 행에 그대로 옮겨진다(ST-C3)
		Map<String, Object> row = coreExhibitionRow(vendorKey);
		assertThat(row.get("title")).isEqualTo("여운 기획전");
		assertThat(row.get("start_date")).isEqualTo(Date.valueOf("2026-08-01"));
		assertThat(row.get("end_date")).isEqualTo(Date.valueOf("2026-12-31"));
		assertThat(row.get("region")).isEqualTo("SEOUL");
		// 장르는 정준 행(exhibition_genre)이 소유한다 - 전시 행에 복제하지 않는다.
		assertThat(jdbcTemplate.queryForMap("select * from exhibition_genre where exhibition_id = ?",
				row.get("id")))
				.containsEntry("genre_keyword", "회화")
				.containsEntry("provider", "GEMINI");
		// 시군구와 좌표도 전시가 아니라 전시장에 붙는 값이라 코어가 그쪽 행에 옮겨 담는다.
		assertThat(jdbcTemplate.queryForMap("select * from exhibition_place where place_key = ?",
				modi.backend.domain.exhibition.hours.PlaceKey.of(placeName)))
				.containsEntry("sigungu", "종로구");
	}

	@Test
	@DisplayName("ST-D2 원장이 없으면 LEDGER_INCOMPLETE 로 멈춘다")
	void 원장이_없으면_LEDGER_INCOMPLETE로_멈춘다() {
		// given 목록은 있으나 장르 원장이 없다
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31");

		// when & then 원장 없이 조립하면 제목과 장르가 빈 전시가 코어에 생긴다
		assertThatThrownBy(() -> stageFacade.stage(vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(StageErrorCode.LEDGER_INCOMPLETE);
		assertThat(coreExhibitionCount(vendorKey)).isZero();
	}

	@Test
	@DisplayName("ST-D3 복원되지 않는 값이 null 이 아니라 예외로 드러난다")
	void 복원되지_않는_값이_예외로_드러난다() {
		// given 점검이 통과시켰을 리 없는 날짜가 원장에 있다(점검과 어셈블의 판단이 어긋난 상황)
		seedListing(vendorKey, placeName, "상시", "2026-12-31");
		seedGenre(vendorKey, "회화", "GEMINI");

		// when & then 조용히 null 이 되면 기간이 깨진 전시가 서비스에 올라간다
		assertThatThrownBy(() -> stageFacade.stage(vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(StageErrorCode.LEDGER_VALUE_MALFORMED);
		assertThat(coreExhibitionCount(vendorKey)).isZero();
	}

	@Test
	@DisplayName("ST-D3 코어 어휘에 없는 장르 공급자도 예외로 드러난다")
	void 코어_어휘에_없는_공급자도_예외로_드러난다() {
		// given
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31");
		seedGenre(vendorKey, "회화", "SOME_NEW_VENDOR");

		// when & then
		assertThatThrownBy(() -> stageFacade.stage(vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(StageErrorCode.LEDGER_VALUE_MALFORMED);
	}

	@Test
	@DisplayName("ST-D4 좌표는 부재면 null 이고 훼손이면 예외다")
	void 좌표는_부재면_null이고_훼손이면_예외다() {
		// given 좌표가 비어 있다(점검 항목이 아니므로 부재는 정상)
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31", null, null);
		seedGenre(vendorKey, "회화", "GEMINI");
		seedDetail(vendorKey, false);
		seedGooglePlace(vendorKey, null, true);

		// when
		stageFacade.stage(vendorKey);

		// then 부재는 통과한다
		assertThat(coreExhibitionRow(vendorKey).get("gps_x")).isNull();

		// given 값이 있는데 숫자가 아니다(훼손)
		String broken = vendorKey + "-broken";
		seedListing(broken, placeName + "-b", "2026-08-01", "2026-12-31", "좌표없음", "37.57");
		seedGenre(broken, "회화", "GEMINI");

		// when & then 훼손을 null 로 흡수하면 ST-D3 의 사고가 좌표에서 되살아난다
		assertThatThrownBy(() -> stageFacade.stage(broken))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(StageErrorCode.LEDGER_VALUE_MALFORMED);
	}

	@Test
	@DisplayName("ST-D5 상세 부재는 정상으로 처리된다")
	void 상세_부재는_정상으로_처리된다() {
		// given 상세 원장이 absent 다(벤더 쪽에서 흔한 경우)
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31");
		seedGenre(vendorKey, "회화", "GEMINI");
		seedDetail(vendorKey, true);
		seedGooglePlace(vendorKey, null, true);

		// when
		stageFacade.stage(vendorKey);

		// then 상세 유래 열은 null 이고 상세 URL 은 목록의 값으로 채워진다
		Map<String, Object> row = coreExhibitionRow(vendorKey);
		assertThat(coreExhibitionCount(vendorKey)).isEqualTo(1);
		assertThat(row.get("detail_url")).isEqualTo("https://exh.example/1001");
	}

	@Test
	@DisplayName("ST-D6 구글 원장의 세 상태가 개장 시간 반영으로 옮겨진다")
	void 구글_원장의_세_상태가_개장_시간_반영으로_옮겨진다() {
		// given 값이 있는 경우
		seedReadyLedger(vendorKey);
		stageFacade.stage(vendorKey);
		assertThat(corePlaceHoursRow(placeName))
				.containsEntry("status", PlaceHoursStatus.SUCCEEDED.name())
				.hasEntrySatisfying("formatted", value -> assertThat(value.toString()).contains("월요일: 휴관"));

		// given 장소를 찾지 못한 경우
		String notFoundKey = vendorKey + "-nf";
		String notFoundPlace = placeName + "-nf";
		seedListing(notFoundKey, notFoundPlace, "2026-08-01", "2026-12-31");
		seedGenre(notFoundKey, "회화", "GEMINI");
		seedGooglePlace(notFoundKey, null, true);
		stageFacade.stage(notFoundKey);
		assertThat(corePlaceHoursRow(notFoundPlace))
				.containsEntry("status", PlaceHoursStatus.NOT_FOUND.name());

		// given 장소는 찾았으나 시간이 없는 경우
		String noHoursKey = vendorKey + "-nh";
		String noHoursPlace = placeName + "-nh";
		seedListing(noHoursKey, noHoursPlace, "2026-08-01", "2026-12-31");
		seedGenre(noHoursKey, "회화", "GEMINI");
		seedGooglePlace(noHoursKey, null, false);
		stageFacade.stage(noHoursKey);
		assertThat(corePlaceHoursRow(noHoursPlace))
				.containsEntry("status", PlaceHoursStatus.NO_HOURS.name());
	}

	@Test
	@DisplayName("ST-D6 구글 원장이 없으면 개장 시간 반영을 생략한다")
	void 구글_원장이_없으면_반영을_생략한다() {
		// given 구글 원장 자체가 없다
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31");
		seedGenre(vendorKey, "회화", "GEMINI");

		// when
		stageFacade.stage(vendorKey);

		// then 조회하지 않은 것과 조회했으나 없는 것이 화면에서 같아 보이면 재조회 대상을 고를 수 없다
		assertThat(coreExhibitionCount(vendorKey)).isEqualTo(1);
		assertThat(corePlaceHoursCount(placeName)).isZero();
	}
}
