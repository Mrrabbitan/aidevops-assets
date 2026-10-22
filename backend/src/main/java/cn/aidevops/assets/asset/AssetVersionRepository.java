package cn.aidevops.assets.asset;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetVersionRepository extends JpaRepository<AssetVersionEntity, String> {
  List<AssetVersionEntity> findByProjectIdAndAssetIdOrderByCreatedAtDesc(
      String projectId, String assetId);
}
