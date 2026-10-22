package cn.aidevops.assets.common;

import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class Jsons {
  public static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Jsons() {}

  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static Map<String, Object> readMap(String value) {
    return value == null || value.isBlank()
        ? new LinkedHashMap<>()
        : MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
  }

  public static List<Map<String, Object>> readList(String value) {
    return value == null || value.isBlank()
        ? new ArrayList<>()
        : MAPPER.readValue(value, new TypeReference<List<Map<String, Object>>>() {});
  }

  public static <T> T read(String value, Class<T> type) {
    return MAPPER.readValue(value, type);
  }
}
