package modi.backend.ingestionv2.inspect.domain;

import java.util.Optional;

/** 점검 애그리거트 포트. */
public interface InspectionRepository {

	Optional<Inspection> findByVendorKey(String vendorKey);

	Inspection save(Inspection inspection);
}
