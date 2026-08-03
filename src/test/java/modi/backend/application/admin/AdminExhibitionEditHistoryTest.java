package modi.backend.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionHistory;
import modi.backend.infra.exhibition.catalog.ExhibitionHistoryJpaRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.support.error.CoreException;

/**
 * 관리자 전시 수정 → 이력(exhibition_history) 검증(이관 6단계).
 * <p>
 * 이 단계의 검증 초점은 사용자가 짚은 <b>"한 번의 수정에 여러 필드가 바뀔 수 있다"</b>이다 — 그 여러 변경이
 * 하나의 이벤트로 묶이는지(같은 editedAt), 그리고 감사에 필요한 old→new가 남는지를 본다.
 * 수정 이력은 <b>쓰기 전용</b>이라(응답에 안 나감) 기존 테스트에 보이지 않는다 — 여기서만 검증된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class AdminExhibitionEditHistoryTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	AdminExhibitionFacade adminExhibitionFacade;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Autowired
	modi.backend.infra.exhibition.catalog.ExhibitionDetailJpaRepository exhibitionDetailRepository;

	@Autowired
	ExhibitionHistoryJpaRepository exhibitionHistoryRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("한 수정에 여러 필드가 바뀌면 같은 이벤트로 묶여 필드별 이력이 남는다(old→new 보존)")
	void 여러필드_한수정_같은이벤트로_묶임() {
		Exhibition e = seed("원제목", "원장소", "무료", "원설명");

		AdminExhibitionResult.Edited result = adminExhibitionFacade.editExhibition(e.getId(),
				"새제목", "새장소", null, "새설명"); // price는 안 건드림(null)

		// 실제로 바뀐 3필드만 이력이 된다(price 제외).
		assertThat(result.changedFields()).isEqualTo(3);
		List<ExhibitionHistory> history = exhibitionHistoryRepository.findByExhibitionIdOrderByEditedAtAscIdAsc(e.getId());
		assertThat(history).hasSize(3);
		// 한 액션이었다 — 세 변경이 같은 editedAt으로 묶인다(field_edits가 필드별로 쪼개 잃던 묶음).
		assertThat(history).extracting(ExhibitionHistory::getEditedAt).containsOnly(history.get(0).getEditedAt());
		// old→new가 남는다(감사).
		assertThat(history).extracting(ExhibitionHistory::getFieldName, ExhibitionHistory::getOldValue,
				ExhibitionHistory::getNewValue)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("title", "원제목", "새제목"),
						org.assertj.core.groups.Tuple.tuple("place", "원장소", "새장소"),
						org.assertj.core.groups.Tuple.tuple("description", "원설명", "새설명"));
		// 전시 자체도 바뀌었다.
		Exhibition reloaded = exhibitionRepository.findById(e.getId()).orElseThrow();
		assertThat(reloaded.getTitle()).isEqualTo("새제목");
		// price는 상세 satellite에 있고 null로 넘겼으므로 불변.
		assertThat(exhibitionDetailRepository.findByExhibitionId(e.getId()).orElseThrow().getPrice())
				.isEqualTo("무료");
	}

	@Test
	@DisplayName("값이 그대로면 이력이 생기지 않는다(멱등 — 같은 값 저장은 사건이 아니다)")
	void 값_동일하면_이력없음() {
		Exhibition e = seed("제목", "장소", "무료", "설명");

		AdminExhibitionResult.Edited result = adminExhibitionFacade.editExhibition(e.getId(),
				"제목", "장소", "무료", "설명"); // 전부 같은 값

		assertThat(result.changedFields()).isZero();
		assertThat(exhibitionHistoryRepository.findByExhibitionIdOrderByEditedAtAscIdAsc(e.getId())).isEmpty();
	}

	@Test
	@DisplayName("여러 번 수정하면 이력이 쌓인다(append-only — field_edits와 정반대로 최신만 남지 않는다)")
	void 여러번_수정_이력_누적() {
		Exhibition e = seed("v0", "장소", "무료", "설명");

		adminExhibitionFacade.editExhibition(e.getId(), "v1", null, null, null);
		adminExhibitionFacade.editExhibition(e.getId(), "v2", null, null, null);

		List<ExhibitionHistory> history = exhibitionHistoryRepository.findByExhibitionIdOrderByEditedAtAscIdAsc(e.getId());
		// 같은 필드(title)를 두 번 고쳐도 두 행이 남는다 — 이력이라 최신 상태로 덮지 않는다.
		assertThat(history).hasSize(2);
		assertThat(history).extracting(ExhibitionHistory::getNewValue).containsExactly("v1", "v2");
		assertThat(history.get(1).getOldValue()).isEqualTo("v1"); // 두 번째 수정의 old는 첫 수정의 new
	}

	@Test
	@DisplayName("없는 전시 수정은 404")
	void 없는전시_404() {
		assertThatThrownBy(() -> adminExhibitionFacade.editExhibition(99_999_999L, "x", null, null, null))
				.isInstanceOf(CoreException.class);
	}

	private Exhibition seed(String title, String place, String price, String description) {
		// resolve-or-create — 같은 이름을 여러 테스트가 seed해도 자연키(정규화 이름) UK를 위반하지 않게.
		Long placeId = modi.backend.domain.exhibition.catalog.ExhibitionTestFactory.placeId(
				exhibitionPlaceRepository, place, ExhibitionRegion.SEOUL);
		Exhibition e = exhibitionRepository.save(Exhibition.createCatalog("EDIT-" + SEQ.getAndIncrement(), title,
				placeId, ExhibitionRegion.SEOUL, null, null, ExhibitionCategory.PAINTING, null, null, "기관"));
		exhibitionDetailRepository.save(modi.backend.domain.exhibition.catalog.ExhibitionDetail.create(
				e.getId(), price, description, null, java.time.LocalDateTime.now()));
		return e;
	}
}
