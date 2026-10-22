package cn.aidevops.assets.task;

import cn.aidevops.assets.task.TaskModels.Run;
import cn.aidevops.assets.task.TaskModels.RunTarget;
import java.util.Map;

/**
 * External adapters must implement probe-before-retry and durable operation ids before activation.
 */
public interface TaskExecutionAdapter {
  boolean supports(String mode);

  Map<String, Object> execute(Run run, RunTarget target);
}
