package modi.backend.ingestion.infra.snapshot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestion.domain.snapshot.GenreSnapshot;

/** Spring Data JPA — 장르 원장(기록성 테이블이라 포트 없이 직사용, 스냅샷 패밀리 공통 규율). */
public interface GenreSnapshotJpaRepository extends JpaRepository<GenreSnapshot, Long> {

	Optional<GenreSnapshot> findByExternalId(String externalId);
}
