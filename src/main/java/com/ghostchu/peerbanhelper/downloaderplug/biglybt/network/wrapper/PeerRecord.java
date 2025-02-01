package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/*
com.biglybt.pif.peers.Peer.java
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class PeerRecord {
    private boolean myPeer;
    private int state;
    private String peerId;
    private String ip;
    private int tcpListenPort;
    private int udpListenPort;
    private int udpNonDataListenPort;
    private int port;
    private boolean lanLocal;
    private boolean transferAvailable;
    private boolean downloadPossible;
    private boolean choked;
    private boolean choking;
    private boolean interested;
    private boolean interesting;
    private boolean seed;
    private boolean snubbed;
    private long snubbedTime;
    private PeerStatsRecord stats;
    private boolean incoming;
    private int percentDoneInThousandNotation;
    private String client;
    private boolean optimisticUnchoke;
    private boolean supportsMessaging;
    private boolean priorityConnection;
    private byte[] handshakeReservedBytes;
    private List<String> peerSupportedMessages;
    private boolean isPBHThrottled;
}
