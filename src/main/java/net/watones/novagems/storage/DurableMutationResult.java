package net.watones.novagems.storage;

import net.watones.novagems.economy.GemTransaction;
import net.watones.novagems.economy.PlayerAccount;

public record DurableMutationResult(Status status, PlayerAccount.Snapshot account, GemTransaction transaction) {
  public enum Status { APPLIED, DUPLICATE, INSUFFICIENT_FUNDS, INVALID_AMOUNT, OVERFLOW, ACCOUNT_MISSING }
  public boolean committed(){return status==Status.APPLIED||status==Status.DUPLICATE;}
}
