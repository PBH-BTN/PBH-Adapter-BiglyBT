package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class PeerManagerRecord {
    private List<PeerRecord> peers;
    private List<PeerDescriptorRecord> pendingPeers;
    private PeerManagerStatsRecord peerStats;
    private boolean isSeeding;
    private boolean isSuperSeeding;
}
