package ist.depchain.core.blockchain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.AccountState;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import java.nio.ByteBuffer;

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
    private SimpleWorld world;

    /** Track all created accounts so we can copy/hash deterministically. */
    private final Set<Address> trackedAccounts = ConcurrentHashMap.newKeySet();

    /** Track storage slots touched for each contract account. */
    private final Map<Address, Set<UInt256>> trackedStorageSlots = new ConcurrentHashMap<>();

    public DepChainWorldState() {
        this.world = new SimpleWorld();
    }

    public SimpleWorld getSimpleWorld() {
        return world;
    }

    public boolean accountExists(Address address) {
        return world.get(address) != null;
    }

    // --- Account creation ---

    public void createEOA(Address address, long nonce, BigInteger balanceUnits) {
        world.createAccount(address, nonce, Wei.of(balanceUnits));
        trackedAccounts.add(address);
        trackedStorageSlots.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet());
    }

    public void createContractAccount(Address address, long nonce, BigInteger balanceUnits, Bytes code) {
        world.createAccount(address, nonce, Wei.of(balanceUnits));
        MutableAccount account = (MutableAccount) world.get(address);
        if (account != null && code != null) {
            account.setCode(code);
        }
        trackedAccounts.add(address);
        trackedStorageSlots.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet());
    }

    // --- Balance operations ---

    public BigInteger getBalance(Address address) {
        AccountState account = world.get(address);
        if (account == null) return BigInteger.ZERO;
        return account.getBalance().toBigInteger();
    }

    public void setBalance(Address address, BigInteger balanceUnits) {
        MutableAccount account = (MutableAccount) world.get(address);
        if (account == null) {
            throw new IllegalStateException("Account does not exist: " + address);
        }
        account.setBalance(Wei.of(balanceUnits));
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
        trackedAccounts.add(address);
        trackedStorageSlots.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet()).add(slot);
    }

    // --- Snapshot / replace ---

    public DepChainWorldState copy() {
        DepChainWorldState cloned = new DepChainWorldState();

        List<Address> orderedAccounts = new ArrayList<>(trackedAccounts);
        orderedAccounts.sort(Comparator.comparing(Address::toHexString));

        for (Address address : orderedAccounts) {
            long nonce = getNonce(address);
            BigInteger balance = getBalance(address);
            Bytes code = getCode(address);

            if (code == null || code.isEmpty()) {
                cloned.createEOA(address, nonce, balance);
            } else {
                cloned.createContractAccount(address, nonce, balance, code);
            }

            Set<UInt256> slots = trackedStorageSlots.getOrDefault(address, Set.of());
            List<UInt256> orderedSlots = new ArrayList<>(slots);
            orderedSlots.sort(Comparator.comparing(UInt256::toHexString));
            for (UInt256 slot : orderedSlots) {
                cloned.setStorageValue(address, slot, getStorageValue(address, slot));
            }
        }

        return cloned;
    }

    public void replaceWith(DepChainWorldState other) {
        this.world = new SimpleWorld();
        this.trackedAccounts.clear();
        this.trackedStorageSlots.clear();

        List<Address> orderedAccounts = new ArrayList<>(other.trackedAccounts);
        orderedAccounts.sort(Comparator.comparing(Address::toHexString));

        for (Address address : orderedAccounts) {
            long nonce = other.getNonce(address);
            BigInteger balance = other.getBalance(address);
            Bytes code = other.getCode(address);

            if (code == null || code.isEmpty()) {
                createEOA(address, nonce, balance);
            } else {
                createContractAccount(address, nonce, balance, code);
            }

            Set<UInt256> slots = other.trackedStorageSlots.getOrDefault(address, Set.of());
            List<UInt256> orderedSlots = new ArrayList<>(slots);
            orderedSlots.sort(Comparator.comparing(UInt256::toHexString));
            for (UInt256 slot : orderedSlots) {
                setStorageValue(address, slot, other.getStorageValue(address, slot));
            }
        }
    }

    /**
     * Deterministic digest of the current world state.
     * Useful to detect accidental divergence across replicas.
     */
    public String computeStateHash() {
        List<byte[]> parts = new ArrayList<>();

        List<Address> orderedAccounts = new ArrayList<>(trackedAccounts);
        orderedAccounts.sort(Comparator.comparing(Address::toHexString));

        for (Address address : orderedAccounts) {
            parts.add(address.toHexString().getBytes(StandardCharsets.UTF_8));
            parts.add(getBalance(address).toString().getBytes(StandardCharsets.UTF_8));
            parts.add(Long.toString(getNonce(address)).getBytes(StandardCharsets.UTF_8));
            parts.add(getCode(address).toHexString().getBytes(StandardCharsets.UTF_8));

            Set<UInt256> slots = trackedStorageSlots.getOrDefault(address, Set.of());
            List<UInt256> orderedSlots = new ArrayList<>(slots);
            orderedSlots.sort(Comparator.comparing(UInt256::toHexString));
            for (UInt256 slot : orderedSlots) {
                parts.add(slot.toHexString().getBytes(StandardCharsets.UTF_8));
                parts.add(getStorageValue(address, slot).toHexString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int total = 0;
        for (byte[] p : parts) {
            total += 4 + p.length;
        }

        ByteBuffer buf = ByteBuffer.allocate(total);
        for (byte[] p : parts) {
            buf.putInt(p.length);
            buf.put(p);
        }

        return Numeric.toHexStringNoPrefix(Hash.sha3(buf.array()));
    }
}