package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadStats;
import com.biglybt.pif.messaging.Message;
import com.biglybt.pif.peers.*;
import com.biglybt.pif.tag.Tag;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.torrent.TorrentAnnounceURLListSet;
import com.biglybt.pifimpl.local.download.DownloadImpl;
import com.biglybt.pifimpl.local.peers.PeerImpl;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper.*;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.util.ByteUtil;

import java.util.*;
import java.util.stream.Collectors;

public class DataConverter {

    public static TorrentRecord getTorrentRecord(Torrent torrent) {
        if (torrent == null) return null;
        return new TorrentRecord(
                torrent.getName(),
                torrent.getHash() == null ? null : ByteUtil.bytesToHex(torrent.getHash()),
                torrent.getSize(),
                torrent.getCreationDate(),
                torrent.getCreatedBy(),
                torrent.getPieceSize(),
                torrent.getPieceCount(),
                torrent.isDecentralised(),
                torrent.isPrivate(),
                torrent.isComplete()
        );
    }

    public static DownloadStatsRecord getDownloadStatsRecord(DownloadStats stats) {
        if (stats == null) return null;
        return new DownloadStatsRecord(
                stats.getStatus(),
                stats.getCompleted(),
                stats.getCheckingDoneInThousandNotation(),
                stats.getDownloaded(),
                stats.getDownloaded(true),
                stats.getRemaining(),
                stats.getRemainingExcludingDND(),
                stats.getUploaded(),
                stats.getUploaded(true),
                stats.getDiscarded(),
                stats.getDownloadAverage(),
                stats.getDownloadAverage(true),
                stats.getUploadAverage(),
                stats.getUploadAverage(true),
                stats.getTotalAverage(),
                stats.getHashFails(),
                stats.getShareRatio(),
                stats.getTimeStarted(),
                stats.getTimeStartedSeeding(),
                stats.getAvailability(),
                stats.getHealth(),
                stats.getBytesUnavailable()
        );
    }

    public static DownloadRecord getDownloadRecord(Download download) {
        if (download == null) return null;
        DownloadImpl downloadImpl = (DownloadImpl) download;
        List<List<String>> trackers = new ArrayList<>();
        for (TorrentAnnounceURLListSet set : downloadImpl.getTorrent().getAnnounceURLList().getSets()) {
            trackers.add(Arrays.stream(set.getURLs()).map(Object::toString).collect(Collectors.toList()));
        }
        DownloadStatsRecord downloadStatsRecord = getDownloadStatsRecord(download.getStats());
        TorrentRecord torrentRecord = getTorrentRecord(download.getTorrent());
        return new DownloadRecord(
                download.getState(),
                download.getSubState(),
                download.getFlags(),
                torrentRecord,
                download.isForceStart(),
                download.isPaused(),
                download.getName(),
                download.getCategoryName(),
                download.getTags().stream().map(Tag::getTagName).collect(Collectors.toList()),
                download.getPosition(),
                download.getCreationTime(),
                downloadStatsRecord,
                download.isComplete(),
                download.isChecking(),
                download.isMoving(),
                download.getDownloadPeerId() == null ? null : ByteUtil.bytesToHex(download.getDownloadPeerId()).toLowerCase(Locale.ROOT),
                download.isRemoved(),
                trackers);
    }

    public static PeerManagerRecord getPeerManagerRecord(PeerManager peerManager) {
        if (peerManager == null) return null;
        return new PeerManagerRecord(
                Arrays.stream(peerManager.getPeers()).map(DataConverter::getPeerRecord).filter(Objects::nonNull).collect(Collectors.toList()),
                Arrays.stream(peerManager.getPendingPeers()).map(DataConverter::getDescriptorRecord).filter(Objects::nonNull).collect(Collectors.toList()),
                getPeerManagerStatsRecord(peerManager.getStats()),
                peerManager.isSeeding(),
                peerManager.isSuperSeeding()
        );
    }

    public static PeerManagerStatsRecord getPeerManagerStatsRecord(PeerManagerStats stats) {
        if (stats == null) return null;
        return new PeerManagerStatsRecord(
                stats.getConnectedSeeds(),
                stats.getConnectedLeechers(),
                stats.getDownloaded(),
                stats.getUploaded(),
                stats.getDownloadAverage(),
                stats.getUploadAverage(),
                stats.getDiscarded(),
                stats.getHashFailBytes(),
                stats.getPermittedBytesToReceive(),
                stats.getPermittedBytesToSend()
        );
    }

    public static PeerDescriptorRecord getDescriptorRecord(PeerDescriptor peerDescriptor) {
        if (peerDescriptor == null) return null;
        return new PeerDescriptorRecord(
                peerDescriptor.getIP(),
                peerDescriptor.getTCPPort(),
                peerDescriptor.getUDPPort(),
                peerDescriptor.useCrypto(),
                peerDescriptor.getPeerSource()
        );
    }

    public static PeerRecord getPeerRecord(Peer peer) {
        if (peer == null) return null;
        String client = peer.getClient();
        if (peer instanceof PeerImpl) {
            client = ((PeerImpl) peer).getDelegate().getClientNameFromExtensionHandshake();
        }
        if (peer.getIp().endsWith(".i2p") || peer.getIp().endsWith(".onion") || peer.getIp().endsWith(".tor"))
            return null;
        com.biglybt.pif.messaging.Message[] messages = new Message[0];
        if (peer.supportsMessaging()) {
            messages = peer.getSupportedMessages();
        }
        var limiters = Plugin.getPBHRateLimiter(peer);
        return new PeerRecord(
                peer.isMyPeer(),
                peer.getState(),
                peer.getId() == null ? null : ByteUtil.bytesToHex(peer.getId()),
                peer.getIp(),
                peer.getTCPListenPort(),
                peer.getUDPListenPort(),
                peer.getUDPNonDataListenPort(),
                peer.getPort(),
                peer.isLANLocal(),
                peer.isTransferAvailable(),
                peer.isDownloadPossible(),
                peer.isChoked(),
                peer.isChoking(),
                peer.isInterested(),
                peer.isInteresting(),
                peer.isSeed(),
                peer.isSnubbed(),
                peer.getSnubbedTime(),
                getPeerStatsRecord(peer.getStats()),
                peer.isIncoming(),
                peer.getPercentDoneInThousandNotation(),
                client,
                peer.isOptimisticUnchoke(),
                peer.supportsMessaging(),
                peer.isPriorityConnection(),
                peer.getHandshakeReservedBytes(),
                Arrays.stream(messages).map(Message::getID).collect(Collectors.toList()),
                limiters.getUploadLimiter() != null || limiters.getDownloadLimiter() != null
        );
    }

    public static PeerStatsRecord getPeerStatsRecord(PeerStats stats) {
        if (stats == null) return null;
        return new PeerStatsRecord(
                stats.getDownloadAverage(),
                stats.getReception(),
                stats.getUploadAverage(),
                stats.getTotalAverage(),
                stats.getTotalDiscarded(),
                stats.getTotalSent(),
                stats.getTotalReceived(),
                stats.getStatisticSentAverage(),
                stats.getPermittedBytesToReceive(),
                stats.getPermittedBytesToSend(),
                stats.getOverallBytesRemaining()
        );
    }
}
