package cn.aidevops.assets.integration;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "assets.role", havingValue = "worker")
public class CollectionScheduleWorker {
  private static final Logger log = LoggerFactory.getLogger(CollectionScheduleWorker.class);
  private final CollectionScheduleService service;
  private final String workerId = "collection-" + UUID.randomUUID();

  public CollectionScheduleWorker(CollectionScheduleService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${assets.collection-poll-ms:5000}")
  public void poll() {
    try {
      service.recover();
      service.enqueueDue();
      for (int i = 0; i < 4; i++) {
        var claim = service.claim(workerId);
        if (claim.isEmpty()) return;
        service.perform(claim.get());
      }
    } catch (Exception e) {
      log.error("Collection scheduler failed; persisted runs remain inspectable", e);
    }
  }
}
