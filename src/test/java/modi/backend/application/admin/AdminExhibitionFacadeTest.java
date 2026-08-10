package modi.backend.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.catalog.ExhibitionDetail;
import modi.backend.infra.exhibition.catalog.ExhibitionDetailJpaRepository;
import modi.backend.infra.exhibition.catalog.ExhibitionHistoryJpaRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;

/**
 * AdminExhibitionFacade.reparseDescriptions 단위 검증 — 설명은 상세 satellite(exhibition_detail)로 이동했으므로 그 행을 대상으로,
 * 마크업이 남은 설명만 평문으로 정리·저장하고 이미 깨끗한 행은 건너뛴다(멱등).
 */
class AdminExhibitionFacadeTest {

	@Test
	@DisplayName("reparseDescriptions — 태그가 남은 상세만 정리·저장하고 깨끗한 상세는 건드리지 않는다")
	void reparse_마크업행만_갱신() {
		ExhibitionRepository exhibitionRepository = mock(ExhibitionRepository.class);
		ExhibitionDetail markup = detail(1L,
				"<!-- wp:paragraph --><p style=\"line-height:1.8;\"><span>배민정 작가는 AI에 입력한다.</span></p>");
		ExhibitionDetail already = detail(2L, "이미 깨끗한 설명이에요.");
		given(exhibitionRepository.findDetailsWithDescription()).willReturn(List.of(markup, already));
		AdminExhibitionFacade facade = new AdminExhibitionFacade(exhibitionRepository,
				mock(ExhibitionPlaceRepository.class), mock(ExhibitionHistoryJpaRepository.class),
				mock(org.springframework.context.ApplicationEventPublisher.class));

		AdminExhibitionResult.DescriptionReparse result = facade.reparseDescriptions();

		assertThat(result.scanned()).isEqualTo(2);
		assertThat(result.updated()).isEqualTo(1);
		assertThat(markup.getDescription()).isEqualTo("배민정 작가는 AI에 입력한다.");
		assertThat(already.getDescription()).isEqualTo("이미 깨끗한 설명이에요.");
		verify(exhibitionRepository, times(1)).saveDetail(markup);
		verify(exhibitionRepository, never()).saveDetail(already);
	}

	private ExhibitionDetail detail(Long exhibitionId, String description) {
		return ExhibitionDetail.create(exhibitionId, null, description, null, LocalDateTime.now());
	}
}
