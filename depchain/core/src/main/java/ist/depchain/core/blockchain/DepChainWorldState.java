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

import ist.depchain.common.utils.ProcessInfo;
import ist.depchain.common.utils.Config;

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
    private Config config; // for debug printing only, to resolve process info for tracked accounts

    /** Track all created accounts so we can copy/hash deterministically. */
    private final Set<Address> trackedAccounts = ConcurrentHashMap.newKeySet();
    // address ->  (slot -> value)
    private final Map<Address, Map<UInt256, UInt256>> trackedStorageValues = new ConcurrentHashMap<>();

    /** Track storage slots touched for each contract account. */
    // address -> set of slots 
    private final Map<Address, Set<UInt256>> trackedStorageSlots = new ConcurrentHashMap<>();

    public DepChainWorldState(Config config) {
        this.world = new SimpleWorld();
        this.config = config;
    }

    public SimpleWorld getSimpleWorld() {
        return world;
    }

    public boolean accountExists(Address address) {
        return world.get(address) != null;
    }

    // --- Account creation ---

    public void createEOA(Address address, long nonce, BigInteger balanceUnits) {
        world.createAccount(address, nonce, Wei.of(balanceUnits)); // we use Besu's Wei wrapper just as a convenient numeric container for balances, but semantically these values represent the smallest unit of DepCoin
        // when we use Wei.of(balanceUnits) (specifically within the context of Java-based EVM implementations), we are assigning native currency (DepCoin) to that account.
        trackedAccounts.add(address);
        trackedStorageSlots.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet());
        trackedStorageValues.computeIfAbsent(address, k -> new ConcurrentHashMap<>());
    }

    public void createContractAccount(Address address, long nonce, BigInteger balanceUnits, Bytes code) {
        world.createAccount(address, nonce, Wei.of(balanceUnits));
        MutableAccount account = (MutableAccount) world.get(address);
        if (account != null && code != null) {
            account.setCode(code);
        }
        trackAccount(address);
    }

    public void trackAccount(Address address) {
        // Record the account so snapshot/copy operations know it must survive.
        trackedAccounts.add(address);
        trackedStorageSlots.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet());
        trackedStorageValues.computeIfAbsent(address, k -> new ConcurrentHashMap<>());
    }

    public void trackStorageSlot(Address address, UInt256 slot) {
        // Track a contract slot that is expected to change during EVM execution.
        trackAccount(address);
        trackedStorageSlots.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet()).add(slot);
    }

    public void refreshTrackedStorage(Address address) {
        // Pull the latest live values from the EVM account into the tracked snapshot map.
        AccountState account = world.get(address);
        if (account == null) {
            return;
        }

        Set<UInt256> slots = trackedStorageSlots.get(address);
        if (slots == null || slots.isEmpty()) {
            return;
        }

        Map<UInt256, UInt256> values = trackedStorageValues.computeIfAbsent(address, k -> new ConcurrentHashMap<>());
        for (UInt256 slot : slots) {
            values.put(slot, account.getStorageValue(slot));
        }
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
        trackedAccounts.add(address);
        trackedStorageSlots.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet());
        trackedStorageValues.computeIfAbsent(address, k -> new ConcurrentHashMap<>());
    }

    public void addBalance(Address address, BigInteger amount) {
        setBalance(address, getBalance(address).add(amount));
    }

    public void subtractBalance(Address address, BigInteger amount) {
        BigInteger current = getBalance(address);
        if (current.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance for " + address + ": has " + current + ", needs " + amount);
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
        // a storage value is a value at a specific slot of a specific contract account and is used to determine the state of a contract account; for example, 
        // for the IST contract, the balance of an address is stored at a specific slot of that address's contract account, and thus to determine the balance 
        // of an address we need to get the storage value at that slot of that address's contract account
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
        trackedStorageValues.computeIfAbsent(address, k -> new ConcurrentHashMap<>()).put(slot, value);
    }

    // --- Snapshot / replace ---

    public DepChainWorldState copy() {
        DepChainWorldState cloned = new DepChainWorldState(config);

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

            Map<UInt256, UInt256> values = trackedStorageValues.getOrDefault(address, Map.of());
            List<UInt256> orderedSlots = new ArrayList<>(values.keySet());
            orderedSlots.sort(Comparator.comparing(UInt256::toHexString));
            for (UInt256 slot : orderedSlots) {
                cloned.setStorageValue(address, slot, values.get(slot));
            }
        }

        return cloned;
    }

    public void replaceWith(DepChainWorldState other) {
        this.world = new SimpleWorld();
        this.trackedAccounts.clear();
        this.trackedStorageSlots.clear();
        this.trackedStorageValues.clear();

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

            Map<UInt256, UInt256> values = other.trackedStorageValues.getOrDefault(address, Map.of());
            List<UInt256> orderedSlots = new ArrayList<>(values.keySet());
            orderedSlots.sort(Comparator.comparing(UInt256::toHexString));
            for (UInt256 slot : orderedSlots) {
                setStorageValue(address, slot, values.get(slot));
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

            Map<UInt256, UInt256> values = trackedStorageValues.getOrDefault(address, Map.of());
            List<UInt256> orderedSlots = new ArrayList<>(values.keySet());
            orderedSlots.sort(Comparator.comparing(UInt256::toHexString));
            for (UInt256 slot : orderedSlots) {
                parts.add(slot.toHexString().getBytes(StandardCharsets.UTF_8));
                parts.add(values.get(slot).toHexString().getBytes(StandardCharsets.UTF_8));
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

    public Set<Address> getTrackedAccounts() {
        return trackedAccounts;
    }

    public String printWorldState() {
        StringBuilder sb = new StringBuilder();
        sb.append("World State:\n");
        List<Address> orderedAccounts = new ArrayList<>(trackedAccounts);
        orderedAccounts.sort(Comparator.comparing(Address::toHexString));
        for (Address address : orderedAccounts) {
            ProcessInfo processInfo = config.getProcessByAddress(address);
            String processId = processInfo != null ? processInfo.getId() : "Unknown";
            sb.append("- ").append(address.toHexString()).append(" (").append(processId).append(")")
                    .append(": balance=").append(getBalance(address))
                    .append(", nonce=").append(getNonce(address))
                    .append("\n");
            Map<UInt256, UInt256> values = trackedStorageValues.getOrDefault(address, Map.of());
            List<UInt256> orderedSlots = new ArrayList<>(values.keySet());
            orderedSlots.sort(Comparator.comparing(UInt256::toHexString));
            for (UInt256 slot : orderedSlots) {
                sb.append("    - storage[").append(slot.toHexString()).append("] = ").append(values.get(slot).toHexString()).append("\n");
            }
        }
        return sb.toString();
    }
}