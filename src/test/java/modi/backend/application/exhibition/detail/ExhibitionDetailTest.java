package modi.backend.application.exhibition.detail;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
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
 * - 상세 서비스 단위 검증(Mockito)
 *   - 서빙 경로가 외부 API를 부르지 않고 조회수만 올린다는 것
 *   - 404·403 판정
 *
 * - 캐시 도입으로 검증 대상이 두 메서드로 갈렸다
 *   - 권한 판정·조립은 {@code assembleShared} — 캐시 미스일 때만 도는 부분
 *   - 조회수·개인화는 {@code personalize} — 캐시 히트든 미스든 매번 도는 부분
 *   - 예전 {@code getDetail}은 지웠다. 남겨 두면 캐시를 타지 않는 두 번째 상세 경로가 된다
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
	@DisplayName("공용 조립 — 상세행이 없어도 외부 API를 부르지 않는다(서빙 경로에서 지연 수집 제거)")
	void 공용조립_외부호출_안함() {
		Exhibition e = catalog("S1", 1L);
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(e));
		given(exhibitionRepository.hasDetail(1L)).willReturn(false); // 상세행 없음 = 예전 지연 수집 조건

		detailService.assembleShared(1L, null);

		// 트랜잭션 안에서 외부 HTTP를 치지 않는다 — 상세 충전은 수집 파이프라인(FETCH_DETAIL)의 몫이다.
		verifyNoInteractions(catalogClient);
		verify(exhibitionRepository, never()).applyDetail(anyLong(), any(), any(), any(), any());
		verify(exhibitionRepository, never()).save(any(Exhibition.class));
	}

	@Test
	@DisplayName("공용 조립은 조회수를 올리지 않는다 — 올리면 캐시 히트마다 누락된다")
	void 공용조립_조회수_안올림() {
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(catalog("S1", 1L)));

		detailService.assembleShared(1L, null);

		// 조회수는 personalize의 몫이다. 여기서 올리면 캐시 히트 경로가 조립을 건너뛰므로 조회가 세어지지 않는다.
		verifyNoInteractions(viewCounter);
	}

	@Test
	@DisplayName("공용 조립은 개인화를 담지 않는다 — 이 값이 그대로 캐시에 들어간다")
	void 공용조립_개인화없음() {
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(catalog("S1", 1L)));

		ExhibitionResult.SharedDetail shared = detailService.assembleShared(1L, 42L);

		assertThat(shared.detail().bookmarked()).isFalse();
		assertThat(shared.detail().recorded()).isFalse();
		verifyNoInteractions(bookmarkRepository, recordJpaRepository);
	}

	@Test
	@DisplayName("CATALOG만 캐시 대상이다 — 캐시에 있다는 사실이 곧 공개 전시라는 증명이 된다")
	void 공용조립_CATALOG만_캐시가능() {
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(catalog("S1", 1L)));
		assertThat(detailService.assembleShared(1L, null).cacheable()).isTrue();

		Exhibition custom = Exhibition.createCustom(10L, "개인 전시", 5L, null, null, null, null, null, null, null);
		ReflectionTestUtils.setField(custom, "id", 4L);
		given(exhibitionRepository.findById(4L)).willReturn(Optional.of(custom));

		// 본인 것이라 403은 아니지만, 캐시에는 넣으면 안 된다.
		assertThat(detailService.assembleShared(4L, 10L).cacheable()).isFalse();
	}

	@Test
	@DisplayName("개인화 — 조회수는 정본 + 누산분으로 나간다")
	void 개인화_조회수_정본더하기누산분() {
		Exhibition e = catalog("S2", 2L);
		given(exhibitionRepository.findById(2L)).willReturn(Optional.of(e));
		given(viewCounter.increase(2L)).willReturn(7L);   // 아직 반영되지 않은 누산분

		ExhibitionResult.Detail shared = detailService.assembleShared(2L, null).detail();

		assertThatCode(() -> detailService.personalize(shared, null)).doesNotThrowAnyException();
		// 정본(0) + 누산분(7). 배치가 옮기기 전에도 사용자가 보는 수는 즉시 오른다.
		assertThat(detailService.personalize(shared, null).viewCount()).isEqualTo(7);
	}

	@Test
	@DisplayName("개인화 — 비로그인이면 관심·기록을 조회하지 않는다")
	void 개인화_비로그인_조회없음() {
		given(exhibitionRepository.findById(2L)).willReturn(Optional.of(catalog("S2", 2L)));
		ExhibitionResult.Detail shared = detailService.assembleShared(2L, null).detail();

		ExhibitionResult.Detail result = detailService.personalize(shared, null);

		assertThat(result.bookmarked()).isFalse();
		assertThat(result.recorded()).isFalse();
		verifyNoInteractions(bookmarkRepository, recordJpaRepository);
	}

	@Test
	@DisplayName("개인화 — 요청자 기준 관심·기록이 캐시된 공용 값 위에 덮인다")
	void 개인화_요청자기준_덮어씀() {
		given(exhibitionRepository.findById(2L)).willReturn(Optional.of(catalog("S2", 2L)));
		ExhibitionResult.Detail shared = detailService.assembleShared(2L, null).detail();
		given(bookmarkRepository.existsActive(42L, 2L)).willReturn(true);
		given(recordJpaRepository.existsByUserIdAndExhibitionIdAndDeletedAtIsNull(42L, 2L)).willReturn(true);

		ExhibitionResult.Detail result = detailService.personalize(shared, 42L);

		assertThat(result.bookmarked()).isTrue();
		assertThat(result.recorded()).isTrue();
		// 공용 값 자체는 그대로여야 한다 — 캐시에 담긴 인스턴스가 오염되면 남에게 새어 나간다.
		assertThat(shared.bookmarked()).isFalse();
		assertThat(shared.recorded()).isFalse();
	}

	@Test
	@DisplayName("개인화는 조회수를 매번 올린다 — 캐시 히트여도 조회가 세어져야 한다")
	void 개인화_매번_조회수증가() {
		given(exhibitionRepository.findById(2L)).willReturn(Optional.of(catalog("S2", 2L)));
		ExhibitionResult.Detail shared = detailService.assembleShared(2L, null).detail();

		detailService.personalize(shared, null);
		detailService.personalize(shared, null);

		verify(viewCounter, org.mockito.Mockito.times(2)).increase(2L);
	}

	@Test
	@DisplayName("존재하지 않는 전시 조회 시 404")
	void 상세_존재하지않으면_404() {
		given(exhibitionRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> detailService.assembleShared(99L, null))
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

		assertThatThrownBy(() -> detailService.assembleShared(3L, 20L))
				.isInstanceOf(CoreException.class)
				.satisfies(ex -> assertThat(((CoreException) ex).errorCode()).isEqualTo(ErrorType.FORBIDDEN));

		verifyNoInteractions(catalogClient);
	}
}
