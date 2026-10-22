import { afterEach, describe, expect, it } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import { defineComponent } from "vue";
import ElementPlus, { ElTag } from "element-plus";
import StatusTag from "./StatusTag.vue";

const mounted: VueWrapper[] = [];
function render(value?: string) {
  const wrapper = mount(StatusTag, {
    props: { value },
    global: { plugins: [ElementPlus], stubs: { transition: false } },
  });
  mounted.push(wrapper);
  return wrapper;
}
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount());
});

describe("StatusTag with real Element Plus rendering", () => {
  it.each([
    ["SIMULATED", "模拟执行"],
    ["REAL", "真实执行"],
    ["ADMITTED", "已入库"],
    ["CANDIDATE", "待入库"],
    ["UNKNOWN", "未知"],
    ["READY", "可用"],
    ["CRITICAL", "严重"],
    ["OPEN", "待处理"],
    ["SUCCEEDED", "成功"],
  ])("renders %s as visible text in a real el-tag", (value, label) => {
    const wrapper = render(value);
    expect(wrapper.findComponent(ElTag).exists()).toBe(true);
    expect(wrapper.get(".el-tag__content").text()).toBe(label);
    expect(wrapper.get(".el-tag").attributes("hidden")).toBeUndefined();
    expect(wrapper.findComponent(ElTag).props("disableTransitions")).toBe(true);
    expect(wrapper.html()).not.toContain("el-zoom-in-center");
  });
  it("updates state and never becomes empty when the value changes", async () => {
    const wrapper = render("RUNNING");
    expect(wrapper.get(".el-tag__content").text()).toBe("执行中");
    await wrapper.setProps({ value: "FAILED" });
    expect(wrapper.get(".el-tag__content").text()).toBe("失败");
    await wrapper.setProps({ value: undefined });
    expect(wrapper.get(".el-tag__content").text()).toBe("—");
  });
  it("renders admission, resource state and execution mode in the same first table render", () => {
    const Host = defineComponent({
      components: { StatusTag },
      template:
        '<table><tbody><tr><td><StatusTag value="ADMITTED" /></td><td><StatusTag value="UNKNOWN" /></td><td><StatusTag value="SIMULATED" /></td></tr></tbody></table>',
    });
    const wrapper = mount(Host, {
      global: { plugins: [ElementPlus], stubs: { transition: false } },
    });
    mounted.push(wrapper);
    expect(
      wrapper.findAll(".el-tag__content").map((tag) => tag.text()),
    ).toEqual(["已入库", "未知", "模拟执行"]);
    expect(
      wrapper
        .findAllComponents(ElTag)
        .every((tag) => tag.props("disableTransitions") === true),
    ).toBe(true);
  });
});
