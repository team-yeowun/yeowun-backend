package modi.backend.ingestion.domain.progress;

import java.util.List;
import java.util.Optional;

/**
 * 진행 상태 저장 포트(Spring 무의존, 구 ExhibitionDraftRepository). 파이프라인이 쓰는 최소 조회만 둔다 —
 * 대시보드성 질의(페이지·집계)는 관리자 파사드가 JpaRepository를 직접 쓴다(슬라이스 내부 실용).
 */
public interface ExhibitionProgressRepository {

	ExhibitionProgress save(ExhibitionProgress progress);

	Optional<ExhibitionProgress> findByExternalId(String externalId);

	/** 같은 전시장을 쓰는 진행 행 전부 — PLACE_STAGED 소비의 시드 해소·신규/기존 마크 대상. */
	List<ExhibitionProgress> findAllByPlaceKey(String placeKey);
}
