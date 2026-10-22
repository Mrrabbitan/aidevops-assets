package cn.aidevops.assets.asset;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "assets")
public class AssetEntity {
  @Id public String id;
  public String projectId;
  public String name;
  public String type;
  public String category = "";
  public String ownerName = "";
  public String environment = "";
  public String dept = "";
  public String location = "";
  public String purpose = "";

  @Column(columnDefinition = "text")
  public String operation = "{}";

  @Column(columnDefinition = "text")
  public String lifecycle = "{}";

  public String mode;
  public String admission = "DRAFT";
  public String resourceStatus = "UNKNOWN";
  public String connectorId = "";
  public String externalId = "";
  public String networkScope = "";
  public String ip = "";

  @Column(columnDefinition = "text")
  public String technicalJson = "{}";

  @Column(columnDefinition = "text")
  public String manualFieldsJson = "{}";

  public Instant observedAt;
  public String source = "MANUAL";
  @Version public long version;
  public Instant createdAt;
  public Instant updatedAt;
  public boolean deleted;
}
