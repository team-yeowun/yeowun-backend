package modi.backend.ingestion.application.progress;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
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
 * 데이터 <b>원장(스냅샷) 쓰기 전담 컴포넌트</b>(설계 §3-6) — 어셈블러(읽기)와 대칭인 쓰기면이다.
 *
 * <p><b>원장 합류 규칙(스펙 §5-1 ⚠ 원장화)</b>: 스냅샷 기록은 더 이상 best-effort가 아니다. 모든 메서드가
 * {@code REQUIRED} 전파로 <b>진행 상태 반영 트랜잭션에 합류</b>하고, 실패를 삼키지 않는다 — [원장 upsert +
 * 마커 + 이벤트]가 전부 성공하거나 전부 실패한다. 그래서 불변식이 성립한다: <b>마커가 있으면 원장이 반드시
 * 있다</b>(어셈블이 스냅샷을 안심하고 읽는 근거).
 *
 * <p>축 서비스가 아니라 컴포넌트인 이유(의존 규칙 §1-1): 진행 상태 서비스가 원장 기록을 자기 tx에 합류시켜야
 * 하는데 서비스→서비스 참조는 금지다. 구글 스냅샷만 여기 없다 — 전시장 축은 원장·정준 반영이 원래 같은
 * 자기 tx라 {@code ExhibitionPlaceService}가 직접 쓴다.
 */
@Component
@RequiredArgsConstructor
public class SnapshotLedger {

	private final CultureListSnapshotJpaRepository listSnapshotRepository;
	private final CultureDetailSnapshotJpaRepository detailSnapshotRepository;
	private final GenreSnapshotJpaRepository genreSnapshotRepository;

	/** 목록 원장 upsert(UK external_id) — 스테이징 tx에 합류. 실패 전파(그 행 전체가 다음 회차로 밀린다). */
	@Transactional
	public void recordList(CatalogExhibitionData data, LocalDateTime syncedAt) {
		listSnapshotRepository.findByExternalId(data.externalId())
				.ifPresentOrElse(row -> {
					row.seenAgain(data, syncedAt);
					listSnapshotRepository.save(row);
				}, () -> listSnapshotRepository.save(CultureListSnapshot.first(data, syncedAt)));
	}

	/** 상세 원장 upsert(응답 verbatim — ADR-13) — 상세 반영 tx에 합류. */
	@Transactional
	public void recordDetail(String externalId, CultureDetailPayload payload) {
		detailSnapshotRepository.findByExternalId(externalId)
				.ifPresentOrElse(row -> {
					row.refresh(payload);
					detailSnapshotRepository.save(row);
				}, () -> detailSnapshotRepository.save(CultureDetailSnapshot.first(externalId, payload)));
	}

	/** 장르 원장 upsert(정제 결과+모델 — 패밀리 정의 확장) — 장르 반영 tx에 합류. */
	@Transactional
	public void recordGenre(String externalId, GenreResult result, LocalDateTime classifiedAt) {
		genreSnapshotRepository.findByExternalId(externalId)
				.ifPresentOrElse(row -> {
					row.refresh(result, classifiedAt);
					genreSnapshotRepository.save(row);
				}, () -> genreSnapshotRepository.save(GenreSnapshot.first(externalId, result, classifiedAt)));
	}
}
