package ist.depchain.core;

import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.HotStuffMessage.Type;
import ist.depchain.common.QC;

import java.util.Collection;
import java.util.Comparator;

public class BasicHotStuffProtocol {
    private final BasicHotStuffUtils utils;
    private final BlockStorage storage;
    private int n, f;
    private int curView = 1;

    // Safety State
    private QC lockedQC = BasicHotStuffUtils.genesisQC;  // [PHASE 3] - The latest QC that was locked
    private QC prepareQC = BasicHotStuffUtils.genesisQC; // [PHASE 1] - The latest prepare QC

    private final String id;

    public BasicHotStuffProtocol(String id, int n, int f) {
        this.id = id;
        this.n = n;
        this.f = f;
        this.storage = new BlockStorage();
        this.utils = new BasicHotStuffUtils(this.storage);
    }

}
