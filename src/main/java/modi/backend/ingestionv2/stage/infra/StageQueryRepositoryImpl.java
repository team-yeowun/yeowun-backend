package modi.backend.ingestionv2.stage.infra;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.stage.domain.StageQueryRepository;
import modi.backend.ingestionv2.stage.domain.Staging;
import modi.backend.ingestionv2.stage.domain.StagingStatus;

/** 관리자 조회 어댑터. 조건과 정렬이 (status, updated_at) 인덱스의 열 순서와 같다. */
@Repository
@RequiredArgsConstructor
public class StageQueryRepositoryImpl implements StageQueryRepository {

	private final StagingJpaRepository jpaRepository;

	@Override
	public List<Staging> findByStatus(StagingStatus status, int offset, int limit) {
		int page = limit == 0 ? 0 : offset / limit;
		return jpaRepository.findByStatusOrderByUpdatedAtDesc(status, PageRequest.of(page, limit));
	}

	@Override
	public long countByStatus(StagingStatus status) {
		return jpaRepository.countByStatus(status);
	}
}
