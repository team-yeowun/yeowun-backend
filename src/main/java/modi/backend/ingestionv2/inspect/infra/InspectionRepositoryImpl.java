package modi.backend.ingestionv2.inspect.infra;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.inspect.domain.Inspection;
import modi.backend.ingestionv2.inspect.domain.InspectionRepository;

/** 점검 애그리거트 포트 어댑터. */
@Repository
@RequiredArgsConstructor
public class InspectionRepositoryImpl implements InspectionRepository {

	private final InspectionJpaRepository inspectionJpaRepository;

	@Override
	public Optional<Inspection> findByVendorKey(String vendorKey) {
		return inspectionJpaRepository.findByVendorKey(vendorKey);
	}

	@Override
	public Inspection save(Inspection inspection) {
		return inspectionJpaRepository.save(inspection);
	}
}
