package cn.aidevops.assets.task;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.task.TaskModels.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class UnconfiguredTaskAdapter implements TaskExecutionAdapter {
  public boolean supports(String mode) {
    return "REAL".equals(mode);
  }

  public Map<String, Object> execute(Run run, RunTarget target) {
    throw new ApiException(503, "NOT_CONFIGURED", "真实执行适配器尚未配置");
  }
}
