package cn.aidevops.assets.asset;

import static org.junit.jupiter.api.Assertions.*;

import cn.aidevops.assets.common.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssetDocumentsTest {
  private final AssetDocuments documents = new AssetDocuments(Jsons.MAPPER);

  @Test
  void csvSupportsQuotedCommasAndMultilineFields() {
    var rows =
        AssetDocuments.parseCsv("name,type,purpose\r\n\"node, a\",SERVER,\"line 1\nline 2\"\r\n");
    assertEquals("node, a", rows.getFirst().get("name"));
    assertEquals("line 1\nline 2", rows.getFirst().get("purpose"));
  }

  @Test
  void exportImportRoundTripsAllFormats() {
    for (String format : List.of("json", "csv", "xlsx")) {
      var row =
          Map.<String, Object>of("name", "节点甲", "type", "SERVER", "category", "研发", "mode", "REAL");
      byte[] exported = documents.export(format, List.of(row));
      var parsed = documents.parse("assets." + format, exported);
      assertEquals("节点甲", parsed.getFirst().get("name"));
      assertEquals("SERVER", parsed.getFirst().get("type"));
    }
  }

  @Test
  void csvNeutralizesSpreadsheetFormulas() {
    String csv =
        new String(
            documents.export("csv", List.of(Map.of("name", "=1+1", "type", "SERVER"))),
            StandardCharsets.UTF_8);
    assertTrue(csv.contains("'=1+1"));
  }

  @Test
  void invalidHeaderAndUnclosedQuoteAreRejected() {
    assertThrows(ApiException.class, () -> AssetDocuments.parseCsv("name,type,name\na,SERVER,a"));
    assertThrows(ApiException.class, () -> AssetDocuments.parseCsv("name,type\n\"broken,SERVER"));
  }
}
