package modi.backend.application.exhibition.detail;


import modi.backend.application.exhibition.ExhibitionCriteria;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionViewCounter;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * {@link ExhibitionDetailService#getDetail} 단위 검증(Mockito). 서빙 상세 경로가 <b>외부 API를 부르지 않고</b>
 * 조회수만 올린다는 것, 그리고 404·403 판정을 다룬다.
 *
 * <p>파사드가 책임별 서비스로 갈리면서 검증 대상도 상세 서비스로 좁혔다 — 조회수·권한은 이 서비스의 몫이고,
 * 파사드는 위임만 하므로 여기서 다시 볼 것이 없다.
 */
class ExhibitionDetailTest {

	private ExhibitionRepository exhibitionRepository;
	private ExhibitionCatalogClient catalogClient;
	private ExhibitionBookmarkRepository bookmarkRepository;
	private RecordJpaRepository recordJpaRepository;
	private ExhibitionPlaceRepository placeRepository;
	private ExhibitionViewCounter viewCounter;
	private ExhibitionDetailService detailService;

	@BeforeEach
	void setUp() {
		exhibitionRepository = mock(ExhibitionRepository.class);
		catalogClient = mock(ExhibitionCatalogClient.class);
		bookmarkRepository = mock(ExhibitionBookmarkRepository.class);
		recordJpaRepository = mock(RecordJpaRepository.class);
		placeRepository = mock(ExhibitionPlaceRepository.class);
		viewCounter = mock(ExhibitionViewCounter.class);
		detailService = new ExhibitionDetailService(exhibitionRepository, placeRepository, bookmarkRepository,
				recordJpaRepository, viewCounter);
		given(viewCounter.increase(anyLong())).willReturn(1L);
		given(placeRepository.findById(anyLong())).willReturn(Optional.of(
				ExhibitionPlace.createFromList("장소", null, null, null, null)));
		given(placeRepository.findHours(anyLong())).willReturn(Optional.empty());
		given(exhibitionRepository.findArtistNames(anyLong())).willReturn(java.util.List.of());
		given(exhibitionRepository.findGenre(anyLong())).willReturn(Optional.empty());
		given(exhibitionRepository.findDetail(anyLong())).willReturn(Optional.empty());
	}

	private Exhibition catalog(String externalId, long id) {
		Exhibition e = Exhibition.createCatalog(externalId, "제목", 5L, null, null, null, null, null, null, "기관");
		ReflectionTestUtils.setField(e, "id", id);
		return e;
	}

	@Test
	@DisplayName("상세 조회 — 상세행이 없어도 외부 API를 부르지 않는다(서빙 경로에서 지연 수집 제거)")
	void 상세조회_외부호출_안함() {
		Exhibition e = catalog("S1", 1L);
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(e));
		given(exhibitionRepository.hasDetail(1L)).willReturn(false); // 상세행 없음 = 예전 지연 수집 조건

		detailService.getDetail(new ExhibitionCriteria.Detail(1L, null));

		// 트랜잭션 안에서 외부 HTTP를 치지 않는다 — 상세 충전은 수집 파이프라인(FETCH_DETAIL)의 몫이다.
		verifyNoInteractions(catalogClient);
		verify(exhibitionRepository, never()).applyDetail(anyLong(), any(), any(), any(), any());
		// 조회수는 누산기로만 간다 — 서빙 경로에 DB 쓰기가 남으면 상세 캐시를 얹어도 왕복이 사라지지 않는다.
		verify(viewCounter).increase(1L);
		verify(exhibitionRepository, never()).save(any(Exhibition.class));
	}

	@Test
	@DisplayName("상세 조회 — 상세행이 없어도 기본 필드로 응답하고, 조회수는 정본 + 누산분으로 나간다")
	void 상세조회_상세행없어도_기본필드로_응답() {
		Exhibition e = catalog("S2", 2L);
		given(exhibitionRepository.findById(2L)).willReturn(Optional.of(e));
		given(exhibitionRepository.hasDetail(2L)).willReturn(false);
		given(viewCounter.increase(2L)).willReturn(7L);   // 아직 반영되지 않은 누산분

		assertThatCode(() -> detailService.getDetail(new ExhibitionCriteria.Detail(2L, null)))
				.doesNotThrowAnyException();

		// 정본(0) + 누산분(7). 배치가 옮기기 전에도 사용자가 보는 수는 즉시 오른다.
		assertThat(detailService.getDetail(new ExhibitionCriteria.Detail(2L, null)).viewCount()).isEqualTo(7);
	}

	@Test
	@DisplayName("존재하지 않는 전시 조회 시 404")
	void 상세_존재하지않으면_404() {
		given(exhibitionRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> detailService.getDetail(new ExhibitionCriteria.Detail(99L, null)))
				.isInstanceOf(CoreException.class)
				.satisfies(ex -> assertThat(((CoreException) ex).errorCode())
						.isEqualTo(ExhibitionErrorCode.EXHIBITION_NOT_FOUND));

		verifyNoInteractions(catalogClient);
	}

	@Test
	@DisplayName("타인의 CUSTOM 전시 조회 시 403")
	void 상세_타인의_CUSTOM_403() {
		Exhibition custom = Exhibition.createCustom(10L, "개인 전시", 5L, null, null, null, null, null, null, null);
		given(exhibitionRepository.findById(3L)).willReturn(Optional.of(custom));

		assertThatThrownBy(() -> detailService.getDetail(new ExhibitionCriteria.Detail(3L, 20L)))
				.isInstanceOf(CoreException.class)
				.satisfies(ex -> assertThat(((CoreException) ex).errorCode()).isEqualTo(ErrorType.FORBIDDEN));

		verifyNoInteractions(catalogClient);
	}
}
