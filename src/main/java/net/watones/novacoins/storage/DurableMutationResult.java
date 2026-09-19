package net.watones.novacoins.storage;

import net.watones.novacoins.economy.CoinTransaction;
import net.watones.novacoins.economy.PlayerAccount;

public record DurableMutationResult(Status status, PlayerAccount.Snapshot account, CoinTransaction transaction) {
  public enum Status { APPLIED, DUPLICATE, INSUFFICIENT_FUNDS, INVALID_AMOUNT, OVERFLOW, ACCOUNT_MISSING }
  public boolean committed(){return status==Status.APPLIED||status==Status.DUPLICATE;}
}
