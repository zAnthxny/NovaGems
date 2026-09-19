package net.watones.novagems.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.MutationKind;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.economy.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalWriterPolishTest {
  @TempDir Path temp;

  @Test
  void journalRemoveFailureKeepsMetadataForRetry() {
    UUID account = UUID.randomUUID();
    EconomyOperation operation = new EconomyOperation(UUID.randomUUID(), account,
        MutationKind.CREDIT, 10, TransactionType.PLAYTIME_REWARD, "test", "test",
        TransactionStatus.COMMITTED, Instant.now(), 77);
    JournalWriter writer = new JournalWriter(new FailFirstRemoveJournal(temp), 8, ignored -> {});
    writer.ready().join();
    writer.store(operation).durable().join();

    assertThatThrownBy(() -> writer.remove(operation.operationId()).join()).hasCauseInstanceOf(
        IOException.class);
    assertThat(writer.metrics().pending()).isOne();
    assertThat(writer.pendingForAccount(account)).isOne();
    assertThat(writer.maxSequence(account)).isEqualTo(77);
    assertThat(writer.hasUnsettledEarlier(account, 78, 0)).isTrue();

    writer.remove(operation.operationId()).join();
    assertThat(writer.metrics().pending()).isZero();
    assertThat(writer.pendingForAccount(account)).isZero();
    assertThat(writer.maxSequence(account)).isZero();
    assertThat(writer.drainAndClose(2, TimeUnit.SECONDS)).isTrue();
  }

  private static final class FailFirstRemoveJournal extends RecoveryJournal {
    private final AtomicBoolean fail = new AtomicBoolean(true);
    FailFirstRemoveJournal(Path directory) { super(directory); }
    @Override public synchronized void remove(UUID operationId) throws IOException {
      if (fail.compareAndSet(true, false)) throw new IOException("simulated remove failure");
      super.remove(operationId);
    }
  }
}
