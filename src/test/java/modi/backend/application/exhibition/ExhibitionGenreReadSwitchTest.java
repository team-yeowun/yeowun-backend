package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import modi.backend.domain.exhibition.catalog.ExhibitionGenre;
import modi.backend.infra.exhibition.catalog.ExhibitionGenreJpaRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreProvider;

/**
 * 장르 <b>읽기·선별이 정준층을 본다</b>는 검증(@SpringBootTest + Testcontainers-MySQL).
 * <p>
 * 이관 2단계-b에서 읽기를 정준층으로 전환했고, <b>7단계에서 레거시 컬럼(exhibitions.genre_keyword)을 제거</b>해
 * 이제 정준층({@code exhibition_genre})이 유일한 출처다. 전환기엔 두 위치를 일부러 갈라놓아 "어느 쪽을 읽나"를
 * 증명했지만, 레거시가 사라진 지금은 출처가 하나뿐이라 <b>정준층 값이 그대로 나가는지</b>와 <b>정준층 부재 시
 * 폴백 없이 빈 배열</b>인지를 본다. (V21 백필 재현 테스트는 원본 컬럼이 사라져 더는 재현할 수 없어 제거했다 —
 * V21은 마이그레이션 체인에서 V26보다 먼저 돌아 이미 제 역할을 한 과거 마이그레이션이다. 백필 대상 선별
 * 케이스는 미분류 스윕 삭제(레거시 뒤채움 계약 제거)와 함께 선별 쿼리가 사라져 제거했다.)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionGenreReadSwitchTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Autowired
	ExhibitionGenreJpaRepository exhibitionGenreRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	// ── (a) 상세 keywords의 출처 ────────────────────────────────────────────────

	@Test
	@DisplayName("상세 keywords는 정준층 값을 그대로 내려보낸다")
	void 상세_keywords_정준층에서_읽는다() {
		Exhibition e = seedCatalog();
		exhibitionGenreRepository.save(ExhibitionGenre.create(e.getId(), "사진",
				GenreProvider.GEMINI, "gemini-2.5-flash-002", LocalDateTime.now()));

		ExhibitionResult.Detail detail = exhibitionFacade.getDetail(
				new ExhibitionCriteria.Detail(e.getId(), null));

		assertThat(detail.keywords()).containsExactly("사진");
	}

	@Test
	@DisplayName("정준층에 행이 없으면 keywords는 빈 배열(폴백 없음 — 출처가 정준층 하나뿐)")
	void 정준층_행없음_빈배열() {
		Exhibition e = seedCatalog();

		ExhibitionResult.Detail detail = exhibitionFacade.getDetail(
				new ExhibitionCriteria.Detail(e.getId(), null));

		assertThat(detail.keywords()).isEmpty();
	}

	@Test
	@DisplayName("스냅샷 조회(getForSnapshot)의 keywords도 같은 출처를 본다")
	void 스냅샷_keywords_정준층에서_읽는다() {
		Exhibition e = seedCatalog();
		exhibitionGenreRepository.save(ExhibitionGenre.create(e.getId(), "공예", GenreProvider.UNKNOWN, null, null));

		ExhibitionResult.Detail detail = exhibitionFacade.getForSnapshot(e.getId(), null);

		assertThat(detail.keywords()).containsExactly("공예");
	}

	// ── 헬퍼 ────────────────────────────────────────────────────────────────────

	private Exhibition seedCatalog() {
		Long placeId = modi.backend.domain.exhibition.catalog.ExhibitionTestFactory.placeId(
				exhibitionPlaceRepository, "시립미술관", ExhibitionRegion.SEOUL);
		Exhibition e = Exhibition.createCatalog("GENRE-READ-" + SEQ.getAndIncrement(), "장르 읽기 전시", placeId,
				null, null, ExhibitionCategory.PAINTING, null, null, "기관");
		return exhibitionRepository.save(e);
	}
}
