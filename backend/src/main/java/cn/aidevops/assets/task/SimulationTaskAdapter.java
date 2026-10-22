package cn.aidevops.assets.task;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.task.TaskModels.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SimulationTaskAdapter implements TaskExecutionAdapter {
  public boolean supports(String mode) {
    return "SIMULATED".equals(mode);
  }

  public Map<String, Object> execute(Run run, RunTarget target) {
    if (!supports(run.mode()) || !"demo".equals(run.projectId()))
      throw new ApiException(422, "SIMULATION_ISOLATION", "模拟器仅允许 demo 隔离项目");
    var config = TaskRules.map(run.snapshot().get("config"));
    if (TaskRules.strings(config.get("simulationFailureTargetIds")).contains(target.targetId()))
      throw new ApiException(502, "SIMULATED_FAILURE", "模拟目标失败；未调用真实系统");
    return Map.of(
        "mode",
        "SIMULATED",
        "observed",
        false,
        "operationId",
        run.id() + ":" + target.targetId(),
        "message",
        Boolean.TRUE.equals(run.snapshot().get("withdraw"))
            ? "模拟撤回首次部署；未调用真实系统"
            : Boolean.TRUE.equals(run.snapshot().get("rollback"))
                ? "模拟恢复冻结的上一版本；未调用真实系统"
                : "模拟执行完成；未调用真实系统");
  }
}
