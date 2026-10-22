import type { components } from "../generated/api";
import { api, query } from "./api";
// OpenAPI is generated from the running Spring application, not hand-maintained schemas.
export type TaskConfig = {
  targetIds?: string[];
  physicalAssetIds?: string[];
  [key: string]: any;
};
export type TaskDto = Omit<
  Required<components["schemas"]["Task"]>,
  "config"
> & { config: TaskConfig };
export type SaveTaskDto = components["schemas"]["SaveRequest"];
export type ValidationDto = components["schemas"]["Validation"];
export function listTasks(projectId: string, kind?: string) {
  return api<TaskDto[]>("/tasks" + query({ projectId, kind }));
}
export function saveTask(body: SaveTaskDto & { id?: string }) {
  return api<TaskDto>("/tasks" + (body.id ? `/${body.id}` : ""), {
    method: body.id ? "PUT" : "POST",
    json: body,
  });
}
