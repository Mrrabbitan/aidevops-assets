package cn.aidevops.assets.alert;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AlertServiceTest {
  @Test
  void thresholdOperatorsHaveCorrectBoundaries() {
    assertFalse(AlertService.compare(80, "GT", 80));
    assertTrue(AlertService.compare(80, "GTE", 80));
    assertTrue(AlertService.compare(0, "EQ", 0));
    assertFalse(AlertService.compare(0, "LT", 0));
    assertTrue(AlertService.compare(0, "LTE", 0));
  }

  @Test
  void invalidOperatorDoesNotBecomeSuccessful() {
    assertFalse(AlertService.compare(95, "INVALID", 80));
  }
}
