package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.biglybt.core.networkmanager.Transport;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadException;
import com.biglybt.pif.download.DownloadStats;
import com.biglybt.pif.ipfilter.IPBanned;
import com.biglybt.pif.ipfilter.IPFilter;
import com.biglybt.pif.peers.*;
import com.biglybt.pif.tag.Tag;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.clientbound.BanBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.clientbound.BanListReplacementBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.clientbound.UnBanBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.serverbound.BatchOperationCallbackBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.serverbound.MetadataCallbackBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper.*;
import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.*;
import java.util.stream.Collectors;

public class Plugin implements UnloadablePlugin {
    public static final Gson GSON = new Gson();
    private static final String PBH_IDENTIFIER = "<PeerBanHelper>";
    private PluginInterface pluginInterface;
    private IntParameter listenPortParam;
    private StringParameter accessKeyParam;
    private BasicPluginConfigModel configModel;
    private JavalinWebContainer webContainer;
    private int port;
    private String token;

    private static TorrentRecord getTorrentRecord(Torrent torrent) {
        if (torrent == null) return null;
        return new TorrentRecord(
                torrent.getName(),
                torrent.getHash() == null ? null : java.util.Base64.getEncoder().encodeToString(torrent.getHash()),
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

    @Override
    public void unload() throws PluginException {
        if (webContainer != null) {
            webContainer.stop();
        }
    }

    @Override
    public void initialize(PluginInterface pluginInterface) {
        this.pluginInterface = pluginInterface;
        configModel = pluginInterface.getUIManager().createBasicPluginConfigModel("peerbanhelper.configui");
        listenPortParam = configModel.addIntParameter2("api-port", "peerbanhelper.port", 7756);
        accessKeyParam = configModel.addStringParameter2("api-token", "peerbanhelper.token", UUID.randomUUID().toString());
        this.port = listenPortParam.getValue();
        this.token = accessKeyParam.getValue();
        listenPortParam.addListener(parameter -> {
            this.port = listenPortParam.getValue();
            reloadPlugin();
        });

        accessKeyParam.addListener(parameter -> {
            this.token = accessKeyParam.getValue();
            reloadPlugin();
        });
        reloadPlugin();
    }

    private void reloadPlugin() {
        if (webContainer != null) {
            webContainer.stop();
        }
        webContainer = new JavalinWebContainer();
        webContainer.start("0.0.0.0", port, token);
        initEndpoints(webContainer.javalin());
    }

    private void initEndpoints(Javalin javalin) {
        javalin .get("/metadata", this::handleMetadata)
                .get("/downloads", this::handleDownloads)
                .get("/download/{infoHash}", this::handleDownload)
                .get("/download/{infoHash}/peers", this::handlePeers)
                .get("/bans", this::handleBans)
                .post("/bans", this::handleBanListApplied)
                .put("/bans", this::handleBanListReplacement)
                .delete("/bans", this::handleBatchUnban);
    }

    private void handleMetadata(Context context) {
        MetadataCallbackBean callbackBean = new MetadataCallbackBean(
                pluginInterface.getPluginVersion(),
                pluginInterface.getApplicationVersion(),
                pluginInterface.getApplicationName(),
                pluginInterface.getAzureusName());
        context.json(callbackBean);
    }

    private void handleBanListApplied(Context context) {
        BanBean banBean = context.bodyAsClass(BanBean.class);
        IPFilter ipFilter = pluginInterface.getIPFilter();
        int success = 0;
        int failed = 0;
        for (String s : banBean.getIps()) {
            try {
                ipFilter.ban(s, PBH_IDENTIFIER);
                success++;
            } catch (Exception e) {
                e.printStackTrace();
                failed++;
            }
        }
        cleanupPeers(banBean.getIps());
        context.status(HttpStatus.OK);
        context.json(new BatchOperationCallbackBean(success, failed));
    }

    private void handleDownloads(Context ctx) {
        List<DownloadRecord> records = new ArrayList<>();
        List<Integer> filter = ctx.queryParams("filter").stream().map(Integer::parseInt).collect(Collectors.toList());
        for (Download download : pluginInterface.getDownloadManager().getDownloads()) {
            boolean shouldAddToResultSet = filter.isEmpty() || filter.contains(download.getState());
            if(shouldAddToResultSet){
                records.add(getDownloadRecord(download));
            }
        }
        ctx.status(HttpStatus.OK);
        ctx.json(records);
    }

    public void handleBans(Context ctx) {
        boolean includeNonPBH = Boolean.parseBoolean(ctx.queryParam("includeNonPBH"));
        List<String> banned = new ArrayList<>();
        for (IPBanned bannedIP : pluginInterface.getIPFilter().getBannedIPs()) {
            if (!includeNonPBH) {
                if (!PBH_IDENTIFIER.equals(bannedIP.getBannedTorrentName())) {
                    continue;
                }
            }
            banned.add(bannedIP.getBannedIP());
        }
        ctx.status(HttpStatus.OK);
        ctx.json(banned);
    }

    private void handleBatchUnban(Context ctx) {
        UnBanBean banBean = ctx.bodyAsClass(UnBanBean.class);
        int unbanned = 0;
        int failed = 0;
        for (String ip : banBean.getIps()) {
            try {
                pluginInterface.getIPFilter().unban(ip);
                unbanned++;
            } catch (Exception e) {
                e.printStackTrace();
                failed++;
            }
        }
        ctx.status(HttpStatus.OK);
        ctx.json(new BatchOperationCallbackBean(unbanned, failed));
    }

    public void handleDownload(Context ctx) {
        String arg = ctx.pathParam("infoHash");
        byte[] infoHash = java.util.Base64.getDecoder().decode(arg);
        try {
            Download download = pluginInterface.getDownloadManager().getDownload(infoHash);
            if (download == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.status(HttpStatus.OK);
            ctx.json(getDownloadRecord(download));
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    public void handlePeers(Context ctx) {
        String arg = ctx.pathParam("infoHash");
        byte[] infoHash = java.util.Base64.getDecoder().decode(arg);
        try {
            Download download = pluginInterface.getDownloadManager().getDownload(infoHash);
            if (download == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.json(getPeerManagerRecord(download.getPeerManager()));
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    public void handleBanListReplacement(Context ctx) {
        BanListReplacementBean replacementBean = ctx.bodyAsClass(BanListReplacementBean.class);
        IPFilter ipFilter = pluginInterface.getIPFilter();
        for (IPBanned blockedIP : ipFilter.getBannedIPs()) {
            if (PBH_IDENTIFIER.equals(blockedIP.getBannedTorrentName()) || replacementBean.isIncludeNonPBHEntries()) {
                ipFilter.unban(blockedIP.getBannedIP());
            }
        }
        int success = 0;
        int failed = 0;
        for (String s : replacementBean.getReplaceWith()) {
            try {
                ipFilter.ban(s, PBH_IDENTIFIER);
                success++;
            } catch (Exception e) {
                e.printStackTrace();
                failed++;
            }
        }
        cleanupPeers(replacementBean.getReplaceWith());
        ctx.status(HttpStatus.OK);
        ctx.json(new BatchOperationCallbackBean(success, failed));
    }

    private void cleanupPeers(List<String> peers) {
        Arrays.stream(pluginInterface.getDownloadManager().getDownloads()).forEach(download -> {
            PeerManager peerManager = download.getPeerManager();
            if (peerManager != null) {
                peers.forEach(ip -> {
                    Peer[] waitToBan = peerManager.getPeers(ip);
                    if (waitToBan != null) {
                        for (Peer peer : waitToBan) {
                            peerManager.removePeer(peer, PBH_IDENTIFIER, Transport.CR_IP_BLOCKED);
                        }
                    }
                });
            }
        });
    }

    private DownloadStatsRecord getDownloadStatsRecord(DownloadStats stats) {
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

    private DownloadRecord getDownloadRecord(Download download) {
        if (download == null) return null;
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
                download.getDownloadPeerId() == null ? null : java.util.Base64.getEncoder().encodeToString(download.getDownloadPeerId()).toLowerCase(Locale.ROOT),
                download.isRemoved());
    }

    private PeerManagerRecord getPeerManagerRecord(PeerManager peerManager) {
        if (peerManager == null) return null;
        return new PeerManagerRecord(
                Arrays.stream(peerManager.getPeers()).map(this::getPeerRecord).collect(Collectors.toList()),
                Arrays.stream(peerManager.getPendingPeers()).map(this::getDescriptorRecord).collect(Collectors.toList()),
                getPeerManagerStatsRecord(peerManager.getStats()),
                peerManager.isSeeding(),
                peerManager.isSuperSeeding()
        );
    }

    private PeerManagerStatsRecord getPeerManagerStatsRecord(PeerManagerStats stats) {
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

    private PeerDescriptorRecord getDescriptorRecord(PeerDescriptor peerDescriptor) {
        if (peerDescriptor == null) return null;
        return new PeerDescriptorRecord(
                peerDescriptor.getIP(),
                peerDescriptor.getTCPPort(),
                peerDescriptor.getUDPPort(),
                peerDescriptor.useCrypto(),
                peerDescriptor.getPeerSource()
        );
    }

    private PeerRecord getPeerRecord(Peer peer) {
        if (peer == null) return null;
        return new PeerRecord(
                peer.isMyPeer(),
                peer.getState(),
                peer.getId() == null ? null : Base64.getEncoder().encodeToString(peer.getId()),
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
                peer.getClient(),
                peer.isOptimisticUnchoke(),
                peer.supportsMessaging(),
                peer.isPriorityConnection()
        );
    }

    private PeerStatsRecord getPeerStatsRecord(PeerStats stats) {
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
