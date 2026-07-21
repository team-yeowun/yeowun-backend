package modi.backend.ingestion.domain.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * ExhibitionDraft 순수 단위 검증 — 라이프사이클(PENDING→ENRICHING→COMPLETED/FAILED)·승격 게이트·재전달 멱등.
 * 초기화 in-flight 상태의 단독 보유자(ADR-10)로서 전이 규칙 전부가 이 Entity 안에 있다.
 */
class ExhibitionDraftTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 19, 12, 0);

	private static CatalogExhibitionData listData(String externalId, String title, String place) {
		LocalDate today = LocalDate.of(2026, 7, 1);
		return new CatalogExhibitionData(externalId, title, place, today, today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관", null, null, null, "전시", "서울");
	}

	private static CatalogDetailData detail() {
		return new CatalogDetailData("무료", "설명", null, "02-000-0000", null, null, "서울시 종로구", null);
	}

	private static GenreResult genre() {
		return GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash");
	}

	@Test
	@DisplayName("stage — 목록으로 PENDING draft가 만들어지고, 원천키·제목 없는 데이터는 거부한다")
	void stage_생성과_거부() {
		ExhibitionDraft draft = ExhibitionDraft.stage(listData("E1", "전시", "장소"));

		assertThat(draft.getStatus()).isEqualTo(DraftStatus.PENDING);
		assertThat(draft.needsDetail()).isTrue();
		assertThat(draft.needsGenre()).isTrue();
		assertThatThrownBy(() -> ExhibitionDraft.stage(listData(null, "전시", "장소")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("스텝 반영 — 첫 반영에서 ENRICHING으로 전이하고, 이미 해소된 스텝의 재전달은 no-op(멱등)")
	void 스텝반영_전이와_멱등() {
		ExhibitionDraft draft = ExhibitionDraft.stage(listData("E1", "전시", "장소"));

		draft.applyDetail(detail(), NOW);
		assertThat(draft.getStatus()).isEqualTo(DraftStatus.ENRICHING);
		assertThat(draft.getPrice()).isEqualTo("무료");

		// 재전달(at-least-once) — 값이 덮이지 않는다.
		draft.applyDetail(new CatalogDetailData("50,000원", "다른 설명", null, null, null, null, null, null), NOW);
		assertThat(draft.getPrice()).isEqualTo("무료");

		draft.applyGenre(genre(), NOW);
		assertThat(draft.getGenreKeyword()).isEqualTo("사진");
		draft.applyGenre(GenreResult.ai("공예", GenreProvider.CLAUDE, "m"), NOW);
		assertThat(draft.getGenreKeyword()).isEqualTo("사진"); // 첫 분류가 이긴다
	}

	@Test
	@DisplayName("승격 게이트 — 목록 코어 + 상세 스텝 해소 + 장르가 전부 있어야 열린다(무상세 확인도 해소로 친다)")
	void 승격게이트() {
		ExhibitionDraft draft = ExhibitionDraft.stage(listData("E1", "전시", "장소"));
		assertThat(draft.isReadyForPromotion()).isFalse(); // 아무 스텝도 안 옴

		draft.applyGenre(genre(), NOW);
		assertThat(draft.isReadyForPromotion()).isFalse(); // 상세 스텝 미해소

		draft.markDetailAbsent(NOW); // 원천 무상세 확인 = 스텝 해소(영구 미승격 방지)
		assertThat(draft.isReadyForPromotion()).isTrue();

		// 전시장 이름이 없으면 승격 불가(전시장 resolve 불능) — 게이트가 막는다.
		ExhibitionDraft noPlace = ExhibitionDraft.stage(listData("E2", "전시", null));
		noPlace.markDetailAbsent(NOW);
		noPlace.applyGenre(genre(), NOW);
		assertThat(noPlace.isReadyForPromotion()).isFalse();
	}

	@Test
	@DisplayName("complete — 게이트 미충족 종료는 프로그래밍 오류로 거부하고, 충족 시 COMPLETED + 전시 id를 남긴다")
	void complete_게이트_강제() {
		ExhibitionDraft draft = ExhibitionDraft.stage(listData("E1", "전시", "장소"));
		assertThatThrownBy(() -> draft.complete(10L, NOW)).isInstanceOf(IllegalStateException.class);

		draft.applyDetail(detail(), NOW);
		draft.applyGenre(genre(), NOW);
		draft.complete(10L, NOW);

		assertThat(draft.getStatus()).isEqualTo(DraftStatus.COMPLETED);
		assertThat(draft.getPromotedExhibitionId()).isEqualTo(10L);
		assertThat(draft.needsDetail()).isFalse();
		assertThat(draft.needsGenre()).isFalse();
	}

	@Test
	@DisplayName("fail — 비종료 draft만 FAILED로 종료하고(원인 보존), 종료 후엔 스텝 반영·갱신이 전부 no-op")
	void fail_종료와_불변() {
		ExhibitionDraft draft = ExhibitionDraft.stage(listData("E1", "전시", "장소"));
		draft.fail("상세 4xx", NOW);

		assertThat(draft.getStatus()).isEqualTo(DraftStatus.FAILED);
		assertThat(draft.getLastError()).isEqualTo("상세 4xx");

		draft.applyDetail(detail(), NOW);
		draft.applyGenre(genre(), NOW);
		draft.refreshFromList(listData("E1", "바뀐 제목", "바뀐 장소"));
		assertThat(draft.getPrice()).isNull(); // 종료 draft는 불변
		assertThat(draft.getTitle()).isEqualTo("전시");
		assertThat(draft.isReadyForPromotion()).isFalse();

		draft.fail("다른 원인", NOW); // 종료 후 재실패는 no-op(원인 보존)
		assertThat(draft.getLastError()).isEqualTo("상세 4xx");
	}

	@Test
	@DisplayName("refreshFromList — 미종료 draft의 목록분을 원천 최신값으로 갱신한다(스텝 해소분은 유지)")
	void refresh_목록분_갱신() {
		ExhibitionDraft draft = ExhibitionDraft.stage(listData("E1", "전시", "장소"));
		draft.applyDetail(detail(), NOW);

		draft.refreshFromList(listData("E1", "갱신된 제목", "갱신된 장소"));

		assertThat(draft.getTitle()).isEqualTo("갱신된 제목");
		assertThat(draft.getPlaceName()).isEqualTo("갱신된 장소");
		assertThat(draft.getPrice()).isEqualTo("무료"); // 상세분은 유지
		assertThat(draft.needsDetail()).isFalse();
	}
}
