package modi.backend.domain.ai;

import java.util.Optional;

/**
 * AI '질문으로 작성' 임시저장(draft) 캐시 포트(저장소 무관). 애플리케이션은 이 인터페이스만 의존하고,
 * 실제 저장소(Redis 등)는 infra 어댑터에서 구현한다(DIP — {@link modi.backend.domain.auth.RefreshTokenStore}와 동일 패턴).
 * 사용자·전시당 진행 중 draft 1개(뒤로가기 복원용). 캐시는 부가 기능이라, 장애로 저장/조회가 실패해도 본 플로우는 진행돼야 한다.
 */
public interface AiDraftStore {

	/** draft를 저장(덮어쓰기)한다. TTL은 구현이 관리한다. */
	void save(Long userId, Long exhibitionId, AiDraft draft);

	/** 저장된 draft를 조회한다(만료·없음·장애 시 empty). */
	Optional<AiDraft> find(Long userId, Long exhibitionId);

	/** draft를 삭제한다(저장 완료·포기 시). 없어도 무해. */
	void delete(Long userId, Long exhibitionId);
}
