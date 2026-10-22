package cn.aidevops.assets.asset;

import cn.aidevops.assets.common.ApiException;
import cn.aidevops.assets.common.Jsons;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AssetDocuments {
  static final List<String> COLUMNS =
      List.of(
          "name",
          "type",
          "category",
          "owner",
          "mode",
          "connectorId",
          "externalId",
          "networkScope",
          "ip",
          "environment",
          "dept",
          "location",
          "purpose",
          "operation",
          "lifecycle",
          "technical",
          "manualFields");
  private final ObjectMapper mapper;

  public AssetDocuments(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> parse(String filename, byte[] bytes) {
    if (bytes.length == 0) throw new ApiException(400, "EMPTY_IMPORT", "导入文件为空");
    if (bytes.length > 20 * 1024 * 1024)
      throw new ApiException(413, "IMPORT_TOO_LARGE", "导入文件不能超过20MB");
    String ext = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    try {
      if (ext.endsWith(".json")) {
        Object value = mapper.readValue(bytes, Object.class);
        if (value instanceof Map<?, ?> map) value = map.get("assets");
        if (!(value instanceof List<?> list))
          throw new ApiException(400, "INVALID_IMPORT", "JSON须为资产数组或含assets数组的对象");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : list) {
          if (!(row instanceof Map<?, ?>))
            throw new ApiException(400, "INVALID_IMPORT", "JSON资产行必须是对象");
          rows.add(new LinkedHashMap<>((Map<String, Object>) row));
        }
        return rows;
      }
      if (ext.endsWith(".csv"))
        return decodeStructures(parseCsv(new String(bytes, StandardCharsets.UTF_8)));
      if (ext.endsWith(".xlsx")) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
          if (workbook.getNumberOfSheets() == 0)
            throw new ApiException(400, "INVALID_IMPORT", "工作簿没有工作表");
          Sheet sheet = workbook.getSheetAt(0);
          Row header = sheet.getRow(0);
          if (header == null) throw new ApiException(400, "INVALID_IMPORT", "工作表缺少表头");
          DataFormatter fmt = new DataFormatter(Locale.ROOT);
          List<String> headers = new ArrayList<>();
          for (int i = 0; i < header.getLastCellNum(); i++)
            headers.add(fmt.formatCellValue(header.getCell(i)).strip());
          validateHeaders(headers);
          List<Map<String, Object>> rows = new ArrayList<>();
          for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
              Cell cell = row.getCell(c);
              if (cell != null && cell.getCellType() == CellType.FORMULA)
                throw new ApiException(
                    400, "FORMULA_NOT_ALLOWED", "导入不接受公式单元格，第" + (index + 1) + "行");
              values.put(headers.get(c), fmt.formatCellValue(cell).strip());
            }
            if (values.values().stream().anyMatch(v -> !v.toString().isBlank())) rows.add(values);
          }
          return decodeStructures(rows);
        }
      }
      throw new ApiException(400, "UNSUPPORTED_IMPORT", "仅支持CSV、JSON和XLSX");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(400, "INVALID_IMPORT", "文件解析失败，请核对格式和表头");
    }
  }

  byte[] export(String format, List<Map<String, Object>> items) {
    try {
      if ("json".equals(format)) return mapper.writeValueAsBytes(Map.of("assets", items));
      if ("csv".equals(format)) {
        StringBuilder csv =
            new StringBuilder("\ufeff").append(String.join(",", COLUMNS)).append("\r\n");
        for (Map<String, Object> item : items) {
          List<String> cells = new ArrayList<>();
          for (String col : COLUMNS) cells.add(csvCell(exportValue(item.get(col))));
          csv.append(String.join(",", cells)).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
      }
      if ("xlsx".equals(format)) {
        try (Workbook wb = new XSSFWorkbook();
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          Sheet sheet = wb.createSheet("资产台账");
          Row h = sheet.createRow(0);
          for (int i = 0; i < COLUMNS.size(); i++) {
            h.createCell(i).setCellValue(COLUMNS.get(i));
            sheet.setColumnWidth(i, 22 * 256);
          }
          int number = 1;
          for (Map<String, Object> item : items) {
            Row row = sheet.createRow(number++);
            for (int i = 0; i < COLUMNS.size(); i++)
              row.createCell(i).setCellValue(exportValue(item.get(COLUMNS.get(i))));
          }
          wb.write(out);
          return out.toByteArray();
        }
      }
      throw new ApiException(400, "UNSUPPORTED_EXPORT", "导出格式必须为csv、json或xlsx");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(500, "EXPORT_FAILED", "导出生成失败");
    }
  }

  private static String exportValue(Object value) {
    return value instanceof Map<?, ?> || value instanceof Collection<?>
        ? Jsons.write(value)
        : Objects.toString(value, "");
  }

  private List<Map<String, Object>> decodeStructures(List<Map<String, Object>> rows) {
    for (Map<String, Object> row : rows) {
      for (String field : List.of("technical", "operation", "lifecycle", "manualFields")) {
        Object value = row.get(field);
        if (value instanceof String text) {
          if (text.isBlank()) {
            row.remove(field);
            continue;
          }
          try {
            row.put(field, mapper.readValue(text, Object.class));
          } catch (Exception e) {
            throw new ApiException(400, "INVALID_STRUCTURE", field + "列必须是有效JSON");
          }
        }
      }
    }
    return rows;
  }

  static String csvCell(String value) {
    if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  static List<Map<String, Object>> parseCsv(String input) {
    List<List<String>> records = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    String text = input.startsWith("\ufeff") ? input.substring(1) : input;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else quoted = !quoted;
      } else if (c == ',' && !quoted) {
        row.add(cell.toString());
        cell.setLength(0);
      } else if ((c == '\n' || c == '\r') && !quoted) {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        row.add(cell.toString());
        cell.setLength(0);
        records.add(row);
        row = new ArrayList<>();
      } else cell.append(c);
    }
    if (quoted) throw new ApiException(400, "INVALID_CSV", "CSV引号未闭合");
    if (cell.length() > 0 || !row.isEmpty()) {
      row.add(cell.toString());
      records.add(row);
    }
    if (records.isEmpty()) throw new ApiException(400, "EMPTY_IMPORT", "CSV为空");
    List<String> headers = records.removeFirst().stream().map(String::strip).toList();
    validateHeaders(headers);
    List<Map<String, Object>> result = new ArrayList<>();
    for (List<String> record : records) {
      if (record.stream().allMatch(String::isBlank)) continue;
      if (record.size() != headers.size())
        throw new ApiException(400, "INVALID_CSV", "CSV行列数量与表头不一致");
      Map<String, Object> values = new LinkedHashMap<>();
      for (int c = 0; c < headers.size(); c++) values.put(headers.get(c), record.get(c).strip());
      result.add(values);
    }
    return result;
  }

  private static void validateHeaders(List<String> headers) {
    if (!headers.containsAll(List.of("name", "type")))
      throw new ApiException(400, "INVALID_HEADER", "表头必须包含name和type");
    if (new HashSet<>(headers).size() != headers.size())
      throw new ApiException(400, "INVALID_HEADER", "表头不能重复");
    if (headers.stream().anyMatch(h -> !COLUMNS.contains(h)))
      throw new ApiException(400, "INVALID_HEADER", "存在不支持的列，请使用导出表头");
  }
}
