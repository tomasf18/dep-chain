package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.AccountState;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;

/**
 * Wraps Besu's SimpleWorld to manage blockchain account state.
 * Supports:
 *  - EOAs: address + native DepCoin balance + nonce
 *  - Contract accounts: address + native balance + nonce + code + storage
 *
 * Internally, balances use Besu's Wei wrapper only as a numeric container.
 * Semantically, these values represent the smallest unit of DepCoin.
 */
public class DepChainWorldState {
    private final SimpleWorld world;

    public DepChainWorldState() {
        this.world = new SimpleWorld();
    }

    public SimpleWorld getSimpleWorld() {
        return world;
    }

    // --- Account creation ---

    public void createEOA(Address address, long nonce, BigInteger balanceWei) {
        world.createAccount(address, nonce, Wei.of(balanceWei));
    }

    public void createContractAccount(Address address, long nonce, BigInteger balanceWei, Bytes code) {
        world.createAccount(address, nonce, Wei.of(balanceWei));
        MutableAccount account = (MutableAccount) world.get(address);
        if (account != null && code != null) {
            account.setCode(code);
        }
    }

    // --- Balance operations ---

    public BigInteger getBalance(Address address) {
        AccountState account = world.get(address);
        if (account == null) return BigInteger.ZERO;
        return account.getBalance().toBigInteger();
    }

    public void setBalance(Address address, BigInteger balanceWei) {
        MutableAccount account = (MutableAccount) world.get(address);
        if (account == null) {
            throw new IllegalStateException("Account does not exist: " + address);
        }
        account.setBalance(Wei.of(balanceWei));
    }

    public void addBalance(Address address, BigInteger amount) {
        setBalance(address, getBalance(address).add(amount));
    }

    public void subtractBalance(Address address, BigInteger amount) {
        BigInteger current = getBalance(address);
        if (current.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance for " + address
                    + ": has " + current + ", needs " + amount);
        }
        setBalance(address, current.subtract(amount));
    }

    // --- Nonce operations ---

    public long getNonce(Address address) {
        AccountState account = world.get(address);
        if (account == null) return 0;
        return account.getNonce();
    }

    public void incrementNonce(Address address) {
        MutableAccount account = (MutableAccount) world.get(address);
        if (account == null) {
            throw new IllegalStateException("Account does not exist: " + address);
        }
        account.incrementNonce();
    }

    // --- Contract operations ---

    public Bytes getCode(Address address) {
        AccountState account = world.get(address);
        if (account == null) return Bytes.EMPTY;
        return account.getCode();
    }

    public void setCode(Address address, Bytes code) {
        MutableAccount account = (MutableAccount) world.get(address);
        if (account == null) {
            throw new IllegalStateException("Account does not exist: " + address);
        }
        account.setCode(code);
    }

    public UInt256 getStorageValue(Address address, UInt256 slot) {
        AccountState account = world.get(address);
        if (account == null) return UInt256.ZERO;
        return account.getStorageValue(slot);
    }

    public void setStorageValue(Address address, UInt256 slot, UInt256 value) {
        MutableAccount account = (MutableAccount) world.get(address);
        if (account == null) {
            throw new IllegalStateException("Account does not exist: " + address);
        }
        account.setStorageValue(slot, value);
    }

    // --- Query ---

    public boolean accountExists(Address address) {
        return world.get(address) != null;
    }
}
