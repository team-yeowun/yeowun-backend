package modi.backend.ingestion.application.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.snapshot.CultureDetailSnapshot;
import modi.backend.ingestion.domain.snapshot.CultureListSnapshot;
import modi.backend.ingestion.domain.snapshot.GenreSnapshot;
import modi.backend.ingestion.infra.snapshot.CultureDetailSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.GenreSnapshotJpaRepository;

/**
 * 어셈블러 단위 — 원장 3종 → 등록 입력 조립: 타입 복원(날짜·좌표·enum)·상세 정제(이스케이프·평문)·무상세 null 조립·
 * 원장 결손 예외(마커⇒원장 불변식 위반 가시화)를 못박는다.
 */
class ExhibitionAssemblerTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 1, 0);

	private CultureListSnapshotJpaRepository listRepo;
	private CultureDetailSnapshotJpaRepository detailRepo;
	private GenreSnapshotJpaRepository genreRepo;
	private ExhibitionAssembler assembler;

	@BeforeEach
	void setUp() {
		listRepo = mock(CultureListSnapshotJpaRepository.class);
		detailRepo = mock(CultureDetailSnapshotJpaRepository.class);
		genreRepo = mock(GenreSnapshotJpaRepository.class);
		assembler = new ExhibitionAssembler(listRepo, detailRepo, genreRepo);
	}

	/** 목록 원장 — 정제 타입이 문자열로 치환돼 저장된 상태를 재현한다(first가 그 치환을 수행). */
	private CultureListSnapshot listSnapshot() {
		CatalogExhibitionData data = new CatalogExhibitionData("EXT-1", "여운전", "국립현대미술관",
				java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 9, 30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
				"http://poster", "http://detail", "전시서비스", 126.98, 37.58, "종로구", "미술", "서울");
		return CultureListSnapshot.first(data, NOW);
	}

	private CultureDetailSnapshot detailSnapshot() {
		CultureDetailPayload payload = mock(CultureDetailPayload.class);
		given(payload.price()).willReturn("성인 15,000원 &amp; 청소년 무료");
		given(payload.contents1()).willReturn("<p>한여름의 <b>여운</b></p>");
		given(payload.url()).willReturn("http://detail2");
		given(payload.phone()).willReturn("02-123-4567");
		given(payload.imgUrl()).willReturn("http://img");
		given(payload.placeUrl()).willReturn("http://place");
		given(payload.placeAddr()).willReturn("서울 종로구 삼청로 30");
		given(payload.placeSeq()).willReturn(null);
		return CultureDetailSnapshot.first("EXT-1", payload);
	}

	@Test
	@DisplayName("정상 조립 — 목록의 문자열 원장이 타입(날짜·좌표·enum)으로 복원되고 상세는 정제(이스케이프·평문)된다")
	void assembles_with_type_restoration() {
		CultureListSnapshot list = listSnapshot();
		CultureDetailSnapshot detail = detailSnapshot(); // 스터빙 밖에서 생성 — first()가 payload mock을 만진다
		GenreSnapshot genre = GenreSnapshot.first("EXT-1",
				new GenreResult("회화", GenreProvider.GEMINI, "gemini-2.5"), NOW);
		given(listRepo.findByExternalId("EXT-1")).willReturn(Optional.of(list));
		given(detailRepo.findByExternalId("EXT-1")).willReturn(Optional.of(detail));
		given(genreRepo.findByExternalId("EXT-1")).willReturn(Optional.of(genre));

		ExhibitionRegistration r = assembler.assemble("EXT-1");

		assertThat(r.title()).isEqualTo("여운전");
		assertThat(r.placeName()).isEqualTo("국립현대미술관");
		assertThat(r.startDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
		assertThat(r.region()).isEqualTo(ExhibitionRegion.SEOUL);
		assertThat(r.category()).isEqualTo(ExhibitionCategory.PAINTING);
		assertThat(r.gpsX()).isEqualTo(126.98);
		assertThat(r.detailUrl()).isEqualTo("http://detail");           // 목록분 우선
		assertThat(r.price()).isEqualTo("성인 15,000원 & 청소년 무료");   // 이스케이프 원복
		assertThat(r.description()).doesNotContain("<p>").contains("여운"); // 평문 추출
		assertThat(r.placeAddr()).isEqualTo("서울 종로구 삼청로 30");
		assertThat(r.genreKeyword()).isEqualTo("회화");
		assertThat(r.genreProvider()).isEqualTo(GenreProvider.GEMINI);
	}

	@Test
	@DisplayName("무상세 전시 — 상세 원장이 없으면 상세분은 null로 조립된다(markDetailAbsent의 정상 경로)")
	void assembles_without_detail() {
		given(listRepo.findByExternalId("EXT-1")).willReturn(Optional.of(listSnapshot()));
		given(detailRepo.findByExternalId("EXT-1")).willReturn(Optional.empty());
		given(genreRepo.findByExternalId("EXT-1")).willReturn(Optional.of(
				GenreSnapshot.first("EXT-1", new GenreResult("회화", GenreProvider.MOCK, null), NOW)));

		ExhibitionRegistration r = assembler.assemble("EXT-1");

		assertThat(r.price()).isNull();
		assertThat(r.description()).isNull();
		assertThat(r.detailUrl()).isEqualTo("http://detail"); // 목록분은 남는다
	}

	@Test
	@DisplayName("원장 결손 — 목록·장르 원장이 없으면 예외(마커⇒원장 불변식 위반 — RETRYABLE로 가시화될 신호)")
	void missing_ledger_throws() {
		given(listRepo.findByExternalId("EXT-1")).willReturn(Optional.empty());
		assertThatThrownBy(() -> assembler.assemble("EXT-1")).isInstanceOf(IllegalStateException.class);

		given(listRepo.findByExternalId("EXT-2")).willReturn(Optional.of(listSnapshot()));
		given(genreRepo.findByExternalId("EXT-2")).willReturn(Optional.empty());
		assertThatThrownBy(() -> assembler.assemble("EXT-2")).isInstanceOf(IllegalStateException.class);
	}
}
