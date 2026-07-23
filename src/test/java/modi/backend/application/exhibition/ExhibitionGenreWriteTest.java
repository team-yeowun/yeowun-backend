package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionGenre;
import modi.backend.infra.exhibition.catalog.ExhibitionGenreJpaRepository;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreProvider;

/**
 * 장르 <b>쓰기</b> 검증(@SpringBootTest + Testcontainers-MySQL).
 * <p>
 * 이관 2단계엔 "쓰기 이중화"(레거시 컬럼 + 정준층)였으나 <b>7단계에서 레거시 컬럼(exhibitions.genre_keyword)을 제거</b>해
 * 이제 정준층({@code exhibition_genre})이 <b>유일한 저장소</b>다. CUSTOM 등록 경로의 정준층 쓰기(값·계보)를 본다.
 * (CATALOG 레거시 백필 케이스는 레거시 뒤채움 계약 삭제와 함께 제거 — CATALOG 쓰기는 승격 등록
 * 계약(ExhibitionRegistrar)이 유일한 경로다.)
 * {@link ExhibitionCatalogClient}는 목으로 두어 부팅 동기화가 외부 공공데이터를 건드리지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionGenreWriteTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;

	@Autowired
	ExhibitionGenreJpaRepository exhibitionGenreRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("CUSTOM 등록(장르 직접 지정) — 정준층에 값+출처(USER)를 남긴다")
	void registerCustom_지정장르_provider_USER() {
		ExhibitionResult.Created created = exhibitionFacade.registerCustom(customCreate("사진"));

		ExhibitionGenre canonical = canonical(created.exhibitionId());
		assertThat(canonical.getGenreKeyword()).isEqualTo("사진");
		assertThat(canonical.getProvider()).isEqualTo(GenreProvider.USER.name());
		assertThat(canonical.getModel()).isNull(); // 사람이 고른 값엔 모델이 없다
		assertThat(canonical.getClassifiedAt()).isNotNull();
		assertThat(canonical.isFallback()).isFalse();
	}

	@Test
	@DisplayName("CUSTOM 등록(장르 미지정) — 기본 분류기(mock)의 결정적 산출은 provider=MOCK으로 드러난다(ADR-11)")
	void registerCustom_미지정_provider_MOCK() {
		// 기본 프로파일의 주 분류기는 mock이다(외부 호출 0·결정적). 계보로 실 AI 분류와 구분된다.
		ExhibitionResult.Created created = exhibitionFacade.registerCustom(customCreate(null));

		ExhibitionGenre canonical = canonical(created.exhibitionId());
		assertThat(GenreKeyword.all()).contains(canonical.getGenreKeyword());
		assertThat(canonical.getProvider()).isEqualTo(GenreProvider.MOCK.name());
		assertThat(canonical.isFallback()).isFalse(); // 폴백값이 아니라 의도된 mock 산출 — 재분류 표식은 레거시 RANDOM만
	}

	private ExhibitionCriteria.CustomCreate customCreate(String genreKeyword) {
		return new ExhibitionCriteria.CustomCreate(9_900_000L + SEQ.getAndIncrement(), "장르 쓰기 전시", null,
				"한가람미술관", null, null, "SEOUL", "PAINTING", null, null, null, genreKeyword);
	}

	private ExhibitionGenre canonical(Long exhibitionId) {
		return exhibitionGenreRepository.findByExhibitionId(exhibitionId).orElseThrow();
	}
}
