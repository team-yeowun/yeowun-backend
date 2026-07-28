package modi.backend.domain.exhibition.catalog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * 전시 애그리거트의 쓰기 진입점(도메인 소유, 구현은 infra — DIP). soft delete된 행은 조회에서 제외한다.
 *
 * <p><b>애그리거트 경계</b>: 루트 {@link Exhibition} 아래에 상세 satellite({@link ExhibitionDetail}, 1:1),
 * 작가 조인({@link ExhibitionArtist}, N:M), 장르({@link ExhibitionGenre}, 정준층)가 속한다.
 * 이 부속들의 영속화는 전부 이 포트를 거친다 — 개별 리포지토리로 반쪽 저장할 경로를 없애 불변식을 한 곳에 모은다.
 *
 * <p><b>부분 갱신({@code applyXxx})이 일급인 이유</b>: 수집 파이프라인(enrichment)은 상세만·장르만 갱신하는
 * 부분 갱신이 기본 단위다. "루트 통째 로드 → 통째 save"를 강제하면 배치가 느려지고 enricher끼리 루트를 두고
 * 경합한다. {@code applyXxx}는 루트 행을 건드리지 않아 서로 충돌하지 않는다.
 *
 * <p>서빙 목록/탐색 조회는 {@link ExhibitionQueryRepository}가 따로 담당한다(쓰기=루트, 읽기=쿼리 분리).
 */
public interface ExhibitionRepository {

	Exhibition save(Exhibition exhibition);

	Optional<Exhibition> findById(Long id);

	/** CATALOG 동기화 upsert용 — 원천 식별자로 기존 행 조회. */
	Optional<Exhibition> findByExternalId(String externalId);

	/** 살아있는 전시들을 id 집합으로 일괄 조회(관심 전시 목록의 벌크 로드용). 정렬·순서 보장 없음. 빈 입력이면 빈 목록. */
	List<Exhibition> findAllActiveByIds(Collection<Long> ids);

	/**
	 * 주어진 id들 중 종료일이 {@code [from, to]} 구간인 살아있는 전시(종료임박 알림 선별용). 빈 입력이면 빈 목록.
	 * 선별을 WHERE로 내려 "전량 읽고 앱에서 버리기"를 없앤다.
	 */
	List<Exhibition> findActiveByIdsAndEndDateBetween(Collection<Long> ids, LocalDate from, LocalDate to);

	// ── 상세 satellite(1:1) — 연관 부재 = 미동기화(ADR-03) ─────────────────────────────

	/** 상세 upsert — 없으면 생성, 있으면 갱신. {@code now}는 동기화 시각(재조회 판정 기준). */
	void applyDetail(Long exhibitionId, String price, String description, String imgUrl, LocalDateTime now);

	/** 원천에 상세가 없음(빈 응답)을 확인한 흔적만 남긴다(멱등) — 재조회 대상에서 빠진다. */
	void markDetailChecked(Long exhibitionId, LocalDateTime now);

	/** 상세행 존재 여부 — "이 전시는 상세 동기화가 끝났는가"의 판정. */
	boolean hasDetail(Long exhibitionId);

	Optional<ExhibitionDetail> findDetail(Long exhibitionId);

	/** 여러 전시의 상세를 일괄 조회(목록 조립 N+1 방지). 빈 입력이면 빈 목록. */
	List<ExhibitionDetail> findDetails(Collection<Long> exhibitionIds);

	/**
	 * 여러 전시의 <b>가격만</b> 조회한다(목록의 free 배지용). 빈 입력이면 빈 맵.
	 * 상세 엔티티를 통째로 읽으면 쓰지도 않는 {@code description}(평균 1KB+)까지 따라온다.
	 */
	Map<Long, String> findPricesByExhibitionIds(Collection<Long> exhibitionIds);

	/** 설명이 있는 상세 전체(관리자 장르 재분류 입력용). */
	List<ExhibitionDetail> findDetailsWithDescription();

	/** 이미 로드한 상세 행의 변경 저장(관리자 수정 경로 — find→entity 행위→save). */
	ExhibitionDetail saveDetail(ExhibitionDetail detail);

	// ── 장르 정준층 ─────────────────────────────────────────────────────────────

	/** 장르 upsert — 없으면 생성, 있으면 재분류(reclassify). 분류 주체·모델은 {@code result}가 안다. */
	void applyGenre(Long exhibitionId, GenreResult result, LocalDateTime now);

	Optional<ExhibitionGenre> findGenre(Long exhibitionId);

	// ── 작가 조인(N:M) — Artist 자체는 독립 애그리거트({@link ArtistRepository}) ─────────

	/** 전시-작가 조인을 멱등하게 잇는다(이미 있으면 무시). */
	void linkArtist(Long exhibitionId, Long artistId);

	/** 한 전시의 작가 이름들(조인 순서대로, 살아있는 작가만). */
	List<String> findArtistNames(Long exhibitionId);
}
