package cn.aidevops.assets.asset;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "asset_previews")
public class AssetPreviewEntity {
  @Id public String id;
  public String projectId;
  public String kind;
  public String actor;
  public String status;

  @Column(columnDefinition = "text")
  public String payloadJson;

  @Column(columnDefinition = "text")
  public String resultJson = "{}";

  public Instant createdAt;
  public Instant expiresAt;
  @Version public long version;
}
