package net.watones.novagems.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.MutationKind;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.economy.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryJournalTest {
  @Test
  void completeTemporaryRecordIsPromotedAfterCrash(@TempDir Path temp) throws Exception {
    Path directory = temp.resolve("crash-recovery");
    RecoveryJournal first = new RecoveryJournal(directory);
    first.initialize();
    EconomyOperation operation = operation();
    first.store(operation);
    Files.move(
        directory.resolve(operation.operationId() + ".op"),
        directory.resolve(operation.operationId() + ".tmp"));

    RecoveryJournal restarted = new RecoveryJournal(directory);
    restarted.initialize();
    assertThat(restarted.loadAll()).containsExactly(operation);
    assertThat(directory.resolve(operation.operationId() + ".tmp")).doesNotExist();
  }

  @Test
  void recordsAreDurableReadableAndIdempotentlyRemoved(@TempDir Path temp) throws Exception {
    RecoveryJournal journal = new RecoveryJournal(temp.resolve("recovery"));
    journal.initialize();
    EconomyOperation operation = operation();
    journal.store(operation);
    journal.store(operation);
    assertThat(journal.loadAll()).containsExactly(operation);
    journal.remove(operation.operationId());
    journal.remove(operation.operationId());
    assertThat(journal.pendingCount()).isZero();
  }

  private EconomyOperation operation() {
    return new EconomyOperation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            MutationKind.CREDIT,
            10,
            TransactionType.PLAYTIME_REWARD,
            "SESSION_INTERVAL",
            "disconnect",
            TransactionStatus.COMMITTED,
            Instant.ofEpochMilli(System.currentTimeMillis()));
  }
}
