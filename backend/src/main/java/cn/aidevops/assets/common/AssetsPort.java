package cn.aidevops.assets.common;

import java.util.List;

public interface AssetsPort {
  List<AssetTarget> requireTargets(String projectId, List<String> ids, boolean ready);

  List<AssetTarget> targets(String projectId);
}
