package com.ragvault.widget.repository;
import com.ragvault.widget.domain.DsRagTable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface DsRagTableRepository extends JpaRepository<DsRagTable, Integer> {
    List<DsRagTable> findByDatasourceId(Integer datasourceId);
    List<DsRagTable> findByDatasourceIdAndIsActiveTrue(Integer datasourceId);
    void deleteByDatasourceId(Integer datasourceId);
}
