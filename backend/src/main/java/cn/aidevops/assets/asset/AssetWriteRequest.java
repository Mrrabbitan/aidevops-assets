package cn.aidevops.assets.asset;

import cn.aidevops.assets.common.Jsons;
import jakarta.validation.constraints.*;
import java.util.*;

/** Stable HTTP write DTO; technical fields vary by the four documented asset types. */
public record AssetWriteRequest(
    @NotBlank String projectId,
    @NotBlank(groups = Create.class) String name,
    @NotBlank(groups = Create.class) @Pattern(regexp = "SERVER|CLIENT|NETWORK|SOFTWARE")
        String type,
    @NotBlank(groups = Create.class) @Pattern(regexp = "REAL|SIMULATED") String mode,
    Long version,
    String category,
    String owner,
    String environment,
    String dept,
    String location,
    String purpose,
    String connectorId,
    String externalId,
    String networkScope,
    String ip,
    Map<String, Object> technical,
    Set<String> manualFields,
    Map<String, Object> operation,
    Map<String, Object> lifecycle) {
  public interface Create {}

  @SuppressWarnings("unchecked")
  public Map<String, Object> toMap() {
    Map<String, Object> result =
        new LinkedHashMap<>((Map<String, Object>) Jsons.MAPPER.convertValue(this, Map.class));
    result.values().removeIf(Objects::isNull);
    return result;
  }
}
