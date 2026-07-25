package modi.backend.ingestion.domain.snapshot;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * AI 장르 분류 결과 원장 — {@code genre_snapshot} 매핑(설계 §5-2 신설). 스냅샷 패밀리의 정의를
 * "벤더 verbatim"에서 <b>"각 스텝의 데이터 원장"</b>으로 확장한 첫 구성원이다: 벤더 원문이 아니라
 * 정제 결과(키워드+공급자+모델)를 남긴다 — 승격 어셈블이 장르분을 읽는 소스.
 *
 * <p>진행 상태({@code exhibition_progress.genre_classified_at})는 마커만 갖고 값은 여기만 갖는다.
 * 원장 합류 규칙: 이 행의 기록은 장르 반영 트랜잭션에 합류한다(마커가 있으면 이 행이 반드시 있다).
 * 멱등 upsert(UK external_id, 1행) — 재전달이 와도 같은 대상 한 행이다.
 */
@Entity
@Table(name = "genre_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GenreSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_id", nullable = false, length = 100)
	private String externalId;

	@Column(name = "genre_keyword", nullable = false, length = 50)
	private String genreKeyword;

	@Enumerated(EnumType.STRING)
	@Column(name = "genre_provider", nullable = false, length = 20)
	private GenreProvider genreProvider;

	/** 요청·응답이 확정한 모델(공급자별 상이, mock이면 null 가능). */
	@Column(name = "genre_model", length = 100)
	private String genreModel;

	@Column(name = "classified_at", nullable = false)
	private LocalDateTime classifiedAt;

	private GenreSnapshot(String externalId, GenreResult result, LocalDateTime classifiedAt) {
		this.externalId = externalId;
		copyFields(result, classifiedAt);
	}

	/** 이 전시의 첫 분류 결과. */
	public static GenreSnapshot first(String externalId, GenreResult result, LocalDateTime classifiedAt) {
		return new GenreSnapshot(externalId, result, classifiedAt);
	}

	/** 재분류 결과로 갱신(전시당 1행 유지 — 재전달·수동 재분류 멱등). */
	public void refresh(GenreResult result, LocalDateTime classifiedAt) {
		copyFields(result, classifiedAt);
	}

	/** 원장 → 코어 어휘 복원 — 어셈블의 장르분 소스. */
	public GenreResult toResult() {
		return new GenreResult(genreKeyword, genreProvider, genreModel);
	}

	private void copyFields(GenreResult result, LocalDateTime classifiedAt) {
		this.genreKeyword = result.genreKeyword();
		this.genreProvider = result.provider();
		this.genreModel = result.model();
		this.classifiedAt = classifiedAt;
	}
}
