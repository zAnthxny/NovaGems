package net.watones.novagems.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.watones.novagems.config.RuntimeConfig;

/** Bounded, best-effort Discord alerts. It never participates in economic outcomes. */
public final class DiscordWebhookAlertService implements AutoCloseable {
  private static final int QUEUE_CAPACITY = 128;
  private static final int MAX_DEDUPE_KEYS = 512;
  private static final long WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private final ThreadPoolExecutor worker;
  private final HttpClient client;
  private final Consumer<String> warningLog;
  private final Map<String, Long> recent = new LinkedHashMap<>();
  private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE);
  private volatile RuntimeConfig.AlertSettings settings;
  private volatile boolean accepting = true;

  public DiscordWebhookAlertService(
      RuntimeConfig.AlertSettings settings, Consumer<String> warningLog) {
    this.settings = settings;
    this.warningLog = warningLog;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        Thread.ofPlatform().daemon(true).name("NovaGems-DiscordWebhook").factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  public void reconfigure(RuntimeConfig.AlertSettings next) {
    settings = next;
    synchronized (recent) { recent.clear(); }
  }

  public boolean alert(String dedupeKey, String title, String detail) {
    RuntimeConfig.AlertSettings active = settings;
    if (!accepting || !active.enabled() || active.webhookUrl().isBlank()) return false;
    long now = System.nanoTime();
    synchronized (recent) {
      Long previous = recent.get(dedupeKey);
      if (previous != null
          && now - previous < TimeUnit.SECONDS.toNanos(active.dedupeSeconds())) return false;
      recent.put(dedupeKey, now);
      while (recent.size() > MAX_DEDUPE_KEYS) {
        recent.remove(recent.keySet().iterator().next());
      }
    }
    try {
      worker.execute(() -> send(active, title, detail));
      return true;
    } catch (RejectedExecutionException full) {
      synchronized (recent) { recent.remove(dedupeKey, now); }
      warnRateLimited("Cola de alertas Discord llena; alerta descartada");
      return false;
    }
  }

  private void send(RuntimeConfig.AlertSettings active, String title, String detail) {
    String content = "🚨 **NovaGems · " + title + "**\n```\n" + detail + "\n```";
    if (content.length() > 1900) content = content.substring(0, 1900) + "…";
    String payload = "{\"content\":\"" + json(content)
        + "\",\"allowed_mentions\":{\"parse\":[]}}";
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(active.webhookUrl()))
          .timeout(Duration.ofMillis(active.timeoutMillis()))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload))
          .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        warnRateLimited("Discord rechazó una alerta (HTTP " + response.statusCode() + ")");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception failure) {
      warnRateLimited("No se pudo enviar una alerta a Discord: "
          + failure.getClass().getSimpleName());
    }
  }

  private String json(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 32);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
          else escaped.append(character);
        }
      }
    }
    return escaped.toString();
  }

  private void warnRateLimited(String message) {
    long now = System.nanoTime();
    long previous = lastWarning.get();
    if ((previous == Long.MIN_VALUE || now - previous >= WARNING_INTERVAL_NANOS)
        && lastWarning.compareAndSet(previous, now)) warningLog.accept(message);
  }

  public int queueSize() { return worker.getQueue().size(); }
  public int queueCapacity() { return QUEUE_CAPACITY; }
  public boolean enabled() { return settings.enabled() && !settings.webhookUrl().isBlank(); }

  @Override
  public void close() {
    accepting = false;
    worker.shutdown();
    try {
      if (!worker.awaitTermination(500, TimeUnit.MILLISECONDS)) worker.shutdownNow();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      worker.shutdownNow();
    }
  }
}
