package modi.backend.ingestionv2.collect.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestionv2.collect.domain.CultureListSnapshot;

/** 목록 원장 Spring Data 리포지토리. 이름이 V1 리포지토리와 겹치지 않도록 원장 어휘를 쓴다. */
public interface ListLedgerJpaRepository extends JpaRepository<CultureListSnapshot, Long> {

	Optional<CultureListSnapshot> findByVendorKey(String vendorKey);
}
