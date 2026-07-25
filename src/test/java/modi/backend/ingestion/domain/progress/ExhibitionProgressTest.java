package modi.backend.ingestion.domain.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.domain.data.CatalogExhibitionData;

/**
 * 진행 상태(구 draft — 슬림) 순수 단위: 스테이징 검증·place_key 파생·마커 멱등·게이트·종료·재개.
 * 값(제목·설명 등)은 원장(스냅샷)으로 이관됐으므로 여기선 마커와 전이만 검증한다.
 */
class ExhibitionProgressTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 12, 0);

	private static CatalogExhibitionData data(String externalId, String title, String place) {
		return new CatalogExhibitionData(externalId, title, place, null, null, null, null,
				null, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("stage — externalId·title 없는 원천은 스테이징할 수 없다")
	void stage_invalid_rejected() {
		assertThatThrownBy(() -> ExhibitionProgress.stage(data(null, "제목", "장소")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ExhibitionProgress.stage(data("EXT-1", " ", "장소")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("stage — 전시장 키는 정규화 이름으로 파생되고, 장소가 비면 null(게이트 영구 차단)이다")
	void stage_derives_place_key() {
		assertThat(ExhibitionProgress.stage(data("EXT-1", "제목", "  국립  현대미술관 ")).getPlaceKey())
				.isEqualTo("국립 현대미술관");
		assertThat(ExhibitionProgress.stage(data("EXT-2", "제목", "  ")).getPlaceKey()).isNull();
		assertThat(ExhibitionProgress.stage(data("EXT-3", "제목", null)).getPlaceKey()).isNull();
	}

	@Test
	@DisplayName("마커 — 상세·장르 해소는 멱등이고(재전달 no-op) 첫 반영에서 ENRICHING으로 전이한다")
	void markers_idempotent() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "제목", "장소"));
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.PENDING);

		progress.markDetailResolved(NOW);
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.ENRICHING);
		LocalDateTime first = progress.getDetailResolvedAt();
		progress.markDetailResolved(NOW.plusHours(1)); // 재전달
		assertThat(progress.getDetailResolvedAt()).isEqualTo(first);

		progress.markGenreClassified(NOW);
		progress.markGenreClassified(NOW.plusHours(1));
		assertThat(progress.getGenreClassifiedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("게이트 — 전시장 키 + 상세 해소 + 장르 마커가 전부 있어야 승격 준비다")
	void gate_requires_all() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "제목", "장소"));
		assertThat(progress.isReadyForPromotion()).isFalse();
		progress.markDetailResolved(NOW);
		assertThat(progress.isReadyForPromotion()).isFalse();
		progress.markGenreClassified(NOW);
		assertThat(progress.isReadyForPromotion()).isTrue();

		// 장소 없는 전시는 마커를 다 채워도 게이트를 못 넘는다.
		ExhibitionProgress noPlace = ExhibitionProgress.stage(data("EXT-2", "제목", null));
		noPlace.markDetailResolved(NOW);
		noPlace.markGenreClassified(NOW);
		assertThat(noPlace.isReadyForPromotion()).isFalse();
		assertThat(noPlace.nextStep()).isEqualTo(ProgressStep.NONE);
	}

	@Test
	@DisplayName("nextStep — 마커 파생: FETCH_DETAIL → CLASSIFY_GENRE → PROMOTE → (종료 후) NONE")
	void next_step_chain() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "제목", "장소"));
		assertThat(progress.nextStep()).isEqualTo(ProgressStep.FETCH_DETAIL);
		progress.markDetailResolved(NOW);
		assertThat(progress.nextStep()).isEqualTo(ProgressStep.CLASSIFY_GENRE);
		progress.markGenreClassified(NOW);
		assertThat(progress.nextStep()).isEqualTo(ProgressStep.PROMOTE);
		progress.complete(77L, NOW);
		assertThat(progress.nextStep()).isEqualTo(ProgressStep.NONE);
	}

	@Test
	@DisplayName("complete — 게이트 미충족 종료는 프로그래밍 오류(예외), 충족 시 전시 id를 남기고 COMPLETED")
	void complete_guarded_by_gate() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "제목", "장소"));
		assertThatThrownBy(() -> progress.complete(1L, NOW)).isInstanceOf(IllegalStateException.class);

		progress.markDetailResolved(NOW);
		progress.markGenreClassified(NOW);
		progress.complete(77L, NOW);
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.COMPLETED);
		assertThat(progress.getPromotedExhibitionId()).isEqualTo(77L);
		assertThat(progress.getCompletedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("fail·reopen — 영구 실패는 FAILED 가시화, 관리자 재개는 FAILED만 ENRICHING으로 되돌린다")
	void fail_and_reopen() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "제목", "장소"));
		progress.fail("상세 404", NOW);
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.FAILED);
		assertThat(progress.getLastError()).isEqualTo("상세 404");

		progress.reopen(NOW.plusDays(1));
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.ENRICHING);
		assertThat(progress.getLastError()).isNull();
		assertThat(progress.getCompletedAt()).isNull();

		// COMPLETED는 재개 대상이 아니다.
		progress.markDetailResolved(NOW);
		progress.markGenreClassified(NOW);
		progress.complete(1L, NOW);
		progress.reopen(NOW);
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.COMPLETED);
	}

	@Test
	@DisplayName("markPlaceOutcome — 첫 판정만 기록한다(재전달·후속 판정은 no-op)")
	void place_outcome_first_wins() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "제목", "장소"));
		progress.markPlaceOutcome(true);
		assertThat(progress.getPlaceOutcome()).isEqualTo(PlaceOutcome.NEW);
		progress.markPlaceOutcome(false);
		assertThat(progress.getPlaceOutcome()).isEqualTo(PlaceOutcome.NEW);
	}

	@Test
	@DisplayName("종료 후 마커는 불변 — 재전달이 와도 COMPLETED/FAILED 행은 손대지 않는다")
	void terminal_rows_immutable() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "제목", "장소"));
		progress.fail("영구 실패", NOW);
		progress.markDetailResolved(NOW);
		progress.markGenreClassified(NOW);
		assertThat(progress.getDetailResolvedAt()).isNull();
		assertThat(progress.getGenreClassifiedAt()).isNull();
	}
}
