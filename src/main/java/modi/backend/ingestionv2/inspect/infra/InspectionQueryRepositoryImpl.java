package modi.backend.ingestionv2.inspect.infra;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.inspect.domain.Inspection;
import modi.backend.ingestionv2.inspect.domain.InspectionQueryRepository;
import modi.backend.ingestionv2.inspect.domain.InspectionStatus;
import modi.backend.ingestionv2.inspect.domain.RejectReason;

/** 관리자 조회 어댑터. 반려 목록을 상태와 사유로 좁혀 최근 순으로 읽는다. */
@Repository
@RequiredArgsConstructor
public class InspectionQueryRepositoryImpl implements InspectionQueryRepository {

	private final InspectionJpaRepository inspectionJpaRepository;

	@Override
	public List<Inspection> findRejected(RejectReason reason, int offset, int limit) {
		int page = limit == 0 ? 0 : offset / limit;
		return inspectionJpaRepository.findByStatusAndReason(InspectionStatus.REJECTED, nameOf(reason),
				PageRequest.of(page, limit));
	}

	@Override
	public long countRejected(RejectReason reason) {
		return inspectionJpaRepository.countByStatusAndReason(InspectionStatus.REJECTED, nameOf(reason));
	}

	private static String nameOf(RejectReason reason) {
		return reason == null ? null : reason.name();
	}
}
