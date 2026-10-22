package cn.aidevops.assets.asset;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "asset_versions")
public class AssetVersionEntity {
  @Id public String id;
  public String assetId;
  public String projectId;
  public long assetVersion;
  public String action;
  public String actor;

  @Column(columnDefinition = "text")
  public String snapshotJson;

  public Instant createdAt;
}
