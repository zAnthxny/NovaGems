package net.watones.novagems.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.watones.novagems.config.RuntimeConfig;
import org.junit.jupiter.api.Test;

class DiscordWebhookAlertServiceTest {
  @Test
  void alertsAreAsyncAndDeduplicated() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> payload = new AtomicReference<>();
    CountDownLatch received = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
      requests.incrementAndGet();
      payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    DiscordWebhookAlertService alerts = new DiscordWebhookAlertService(
        new RuntimeConfig.AlertSettings(true,
            "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", 2000, 60),
        ignored -> {});
    try {
      assertThat(alerts.alert("same", "Test", "safe detail")).isTrue();
      assertThat(alerts.alert("same", "Test", "duplicate")).isFalse();
      assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(requests).hasValue(1);
      assertThat(payload.get()).contains("NovaGems", "safe detail", "allowed_mentions");
      assertThat(alerts.queueCapacity()).isEqualTo(128);
    } finally {
      alerts.close();
      server.stop(0);
    }
  }

  @Test
  void disabledWebhookDoesNotQueueWork() {
    DiscordWebhookAlertService alerts = new DiscordWebhookAlertService(
        new RuntimeConfig.AlertSettings(false, "", 2000, 60), ignored -> {});
    try {
      assertThat(alerts.alert("disabled", "Test", "detail")).isFalse();
      assertThat(alerts.queueSize()).isZero();
    } finally {
      alerts.close();
    }
  }
}
