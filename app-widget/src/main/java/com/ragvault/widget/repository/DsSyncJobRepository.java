package com.ragvault.widget.repository;
import com.ragvault.widget.domain.DsSyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface DsSyncJobRepository extends JpaRepository<DsSyncJob, Integer> {
    List<DsSyncJob> findByDatasourceIdOrderByStartedAtDesc(Integer datasourceId);
    List<DsSyncJob> findByDatasourceIdAndTableNameOrderByStartedAtDesc(Integer datasourceId, String tableName);
}
