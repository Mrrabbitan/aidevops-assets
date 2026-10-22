package cn.aidevops.assets.asset;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository
    extends JpaRepository<AssetEntity, String>, JpaSpecificationExecutor<AssetEntity> {
  List<AssetEntity> findByProjectIdAndDeletedFalseOrderByUpdatedAtDesc(String projectId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AssetEntity a where a.id=:id")
  Optional<AssetEntity> locked(@Param("id") String id);
}
