package modi.backend.ingestion.infra;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestion.domain.entity.CultureListSnapshot;

public interface CultureListSnapshotJpaRepository extends JpaRepository<CultureListSnapshot, Long> {

	Optional<CultureListSnapshot> findByExternalId(String externalId);

	/**
	 * 주어진 식별자 중 <b>이미 스냅샷이 있는</b> 것의 수 — 목록 순회 조기 종료 판정용(페이지당 1회).
	 * 엔티티를 싣지 않고 수만 센다(판정에 값이 필요 없다).
	 */
	long countByExternalIdIn(Collection<String> externalIds);
}
