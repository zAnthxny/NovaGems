package net.watones.novagems.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.MutationKind;
import net.watones.novagems.economy.TransactionStatus;
import net.watones.novagems.economy.TransactionType;
import net.watones.novagems.economy.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryReplayTest {
  @Test
  void restartReplayAppliesPendingOperationExactlyOnce(@TempDir Path temp) throws Exception {
    SQLiteStorageProvider storage = new SQLiteStorageProvider(temp.resolve("replay.db"));
    storage.initialize();
    UUID accountId = UUID.randomUUID();
    storage.loadOrCreate(accountId, "Replay");
    EconomyOperation operation =
        new EconomyOperation(
            UUID.randomUUID(),
            accountId,
            MutationKind.CREDIT,
            10,
            TransactionType.PLAYTIME_REWARD,
            "SESSION_INTERVAL",
            "restart",
            TransactionStatus.COMMITTED,
            Instant.ofEpochMilli(System.currentTimeMillis()));
    RecoveryJournal journal = new RecoveryJournal(temp.resolve("recovery"));
    journal.initialize();
    journal.store(operation);

    WalletService first =
        new WalletService(storage, temp.resolve("recovery"), error -> {}, 32, 8, 32);
    assertThat(first.replayRecovery().join().recovered()).isEqualTo(1);
    assertThat(first.replayRecovery().join().recovered()).isZero();
    first.close();

    assertThat(storage.loadAccount(accountId).orElseThrow().balance()).isEqualTo(10);
    assertThat(storage.history(accountId, 0, 10)).hasSize(1);
    journal.initialize();
    assertThat(journal.pendingCount()).isZero();
    storage.close();
  }
}
