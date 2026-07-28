package modi.backend.domain.exhibition.catalog;

import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreProvider;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 전시 장르 분류 결과(정준층 — 벤더 무관).
 *
 * <p>이 축엔 벤더 원본 테이블이 없다. 요청에서 구조화 출력(enum 스키마)으로 응답을 우리 어휘로 미리 강제하므로
 * 응답 → 정준 변환이 무손실이고, 원본을 따로 남기면 이 테이블과 같은 내용의 복사본이 될 뿐이기 때문이다.
 * 따라서 이 테이블이 유일한 저장소이고, 호출 기록은 {@code external_api_call}이 맡는다.
 *
 * <p>{@code provider}가 푸는 문제: 출처를 남기지 않으면 랜덤 폴백값이 AI 분류값과 구분되지 않아,
 * 한 번 저장되는 순간 "미분류(IS NULL)" 대상에서 빠져 영구 이탈한다. provider를 남기면 선별 재분류가 가능하다.
 *
 * <p>{@code model}은 응답의 modelVersion을 우선한다 — 요청 모델은 별칭(alias)일 수 있어 실제 서빙 모델과
 * 다를 수 있고, 진실은 응답에 있다. 모델 업그레이드 시 구모델 산출분만 선별 재분류하는 데 쓴다.
 *
 * <p>{@link Exhibition}과는 ID로만 참조한다(경계 넘는 @ManyToOne ❌). 정준층은 도메인과 생명주기가 다르고
 * 원본에서 재생성될 수 있는 층이다.
 */
@Entity
@Table(name = "exhibition_genre")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExhibitionGenre {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "exhibition_id", nullable = false)
	private Long exhibitionId;

	/** 마스터({@link GenreKeyword}) 중 1개. */
	@Column(name = "genre_keyword", length = 50)
	private String genreKeyword;

	@Column(name = "provider", nullable = false, length = 20)
	private String provider;

	/** 응답 modelVersion(있으면). RANDOM·USER 출처엔 모델이 없다. */
	@Column(name = "model", length = 50)
	private String model;

	@Column(name = "classified_at")
	private LocalDateTime classifiedAt;

	private ExhibitionGenre(Long exhibitionId, String genreKeyword, GenreProvider provider, String model,
			LocalDateTime classifiedAt) {
		this.exhibitionId = exhibitionId;
		this.genreKeyword = genreKeyword;
		this.provider = provider.name();
		this.model = model;
		this.classifiedAt = classifiedAt;
	}

	public static ExhibitionGenre create(Long exhibitionId, String genreKeyword, GenreProvider provider, String model,
			LocalDateTime classifiedAt) {
		return new ExhibitionGenre(exhibitionId, genreKeyword, provider, model, classifiedAt);
	}

	/**
	 * 재분류 결과를 반영한다. 분류는 덮어쓰기가 정상 동작이다(모델 업그레이드·폴백 복구 시 재분류하므로).
	 * 다만 빈 값으로는 덮지 않는다 — 분류기가 값을 못 낸 경우 기존 분류를 잃을 이유가 없다.
	 */
	public void reclassify(String genreKeyword, GenreProvider provider, String model, LocalDateTime classifiedAt) {
		if (genreKeyword == null || genreKeyword.isBlank()) {
			return;
		}
		this.genreKeyword = genreKeyword.trim();
		this.provider = provider.name();
		this.model = model;
		this.classifiedAt = classifiedAt;
	}

	/** 랜덤 폴백으로 채워진 행인가 — 선별 재분류 대상 판별. */
	public boolean isFallback() {
		return GenreProvider.RANDOM.name().equals(this.provider);
	}
	/**
	 * 응답에 실을 장르 키워드 목록. 미분류(행 없음·값 없음·공백)면 빈 목록이다 —
	 * "장르가 무엇인가"의 판단은 도메인이 하고, 호출부는 결과만 받는다.
	 */
	public static java.util.List<String> keywordsOf(ExhibitionGenre genre) {
		if (genre == null || genre.genreKeyword == null || genre.genreKeyword.isBlank()) {
			return java.util.List.of();
		}
		return java.util.List.of(genre.genreKeyword);
	}

}
