package cn.aidevops.assets.asset;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AssetPreviewRepository extends JpaRepository<AssetPreviewEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from AssetPreviewEntity p where p.id=:id")
  Optional<AssetPreviewEntity> locked(@Param("id") String id);
}
