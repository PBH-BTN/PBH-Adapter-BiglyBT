package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.networkmanager.Transport;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.clientid.ClientIDGenerator;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadException;
import com.biglybt.pif.download.DownloadStats;
import com.biglybt.pif.ipfilter.IPBanned;
import com.biglybt.pif.ipfilter.IPFilter;
import com.biglybt.pif.ipfilter.IPFilterException;
import com.biglybt.pif.messaging.Message;
import com.biglybt.pif.peers.*;
import com.biglybt.pif.tag.Tag;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pifimpl.local.clientid.ClientIDManagerImpl;
import com.biglybt.pifimpl.local.peers.PeerImpl;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.ConnectorData;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class Plugin implements UnloadablePlugin {
    public static final Gson GSON = new Gson();
    private static final String PBH_IDENTIFIER = "<PeerBanHelper>";
    private static final Lock BAN_LIST_OPERATION_LOCK = new ReentrantLock();
    private PluginInterface pluginInterface;
    private IntParameter listenPortParam;
    private StringParameter accessKeyParam;
    private BasicPluginConfigModel configModel;
    private JavalinWebContainer webContainer;
    private int port;
    private String token;
    private PluginConfig cfg;
    private ConnectorData connectorData;
    private ClientIDGenerator clientIDGeneratorOriginal;
    private boolean useClientIdModifier;
    private BooleanParameter clientIdModifier;

    private static TorrentRecord getTorrentRecord(Torrent torrent) {
        if (torrent == null) return null;
        return new TorrentRecord(
                torrent.getName(),
                torrent.getHash() == null ? null : bytesToHex(torrent.getHash()),
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

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte aByte : bytes) {
            String hex = Integer.toHexString(aByte & 0xFF);
            if (hex.length() < 2) {
                sb.append(0);
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * hex字符串转byte数组
     *
     * @param inHex 待转换的Hex字符串
     * @return 转换后的byte数组结果
     */
    public static byte[] hexToByteArray(String inHex) {
        int hexlen = inHex.length();
        byte[] result;
        if (hexlen % 2 == 1) {
            //奇数
            hexlen++;
            result = new byte[(hexlen / 2)];
            inHex = "0" + inHex;
        } else {
            //偶数
            result = new byte[(hexlen / 2)];
        }
        int j = 0;
        for (int i = 0; i < hexlen; i += 2) {
            result[j] = hexToByte(inHex.substring(i, i + 2));
            j++;
        }
        return result;
    }

    /**
     * Hex字符串转byte
     *
     * @param inHex 待转换的Hex字符串
     * @return 转换后的byte
     */
    public static byte hexToByte(String inHex) {
        return (byte) Integer.parseInt(inHex, 16);
    }

    public void runIPFilterOperation(Runnable runnable) throws IPFilterException {
        BAN_LIST_OPERATION_LOCK.lock();
        try {
            var originalPersistentSetting = COConfigurationManager.getBooleanParameter("Ip Filter Banning Persistent");
            //originalPersistentSetting = false;
            try {
                COConfigurationManager.setParameter("Ip Filter Banning Persistent", false);
                COConfigurationManager.setParameter("Ip Filter Ban Block Limit", 256);
                runnable.run();
            } finally {
                COConfigurationManager.setParameter("Ip Filter Banning Persistent", originalPersistentSetting);
                if (originalPersistentSetting) {
                    this.pluginInterface.getIPFilter().save();
                }
            }
        } finally {
            BAN_LIST_OPERATION_LOCK.unlock();
        }
    }

    @Override
    public void unload() throws PluginException {
        if (webContainer != null) {
            webContainer.stop();
        }
        if (clientIDGeneratorOriginal != null) {
            ClientIDManagerImpl.getSingleton().setGenerator(clientIDGeneratorOriginal, true);
        }
    }

    @Override
    public void initialize(PluginInterface pluginInterface) {
        this.pluginInterface = pluginInterface;
        this.cfg = pluginInterface.getPluginconfig();
        this.port = cfg.getPluginIntParameter("web.port", 7759);
        this.token = cfg.getPluginStringParameter("web.token", UUID.randomUUID().toString());
        this.useClientIdModifier = cfg.getPluginBooleanParameter("bt.useClientIdModifier", true);
        configModel = pluginInterface.getUIManager().createBasicPluginConfigModel("peerbanhelper.configui");
        listenPortParam = configModel.addIntParameter2("api-port", "peerbanhelper.port", port);
        listenPortParam.addListener(lis -> {
            this.port = listenPortParam.getValue();
            saveAndReload();
        });
        accessKeyParam = configModel.addStringParameter2("api-token", "peerbanhelper.token", token);
        accessKeyParam.addListener(lis -> {
            this.token = accessKeyParam.getValue();
            saveAndReload();
        });
        clientIdModifier = configModel.addBooleanParameter2("use-client-id-modifier", "peerbanhelper.clientIdModifier", useClientIdModifier);
        clientIdModifier.addListener(lis -> {
            this.useClientIdModifier = clientIdModifier.getValue();
            saveAndReload();
        });
        saveAndReload();
        clientIDGeneratorOriginal = ClientIDManagerImpl.getSingleton().getGenerator();
        ClientIDManagerImpl.getSingleton().setGenerator(new PBHClientIDGenerator(this, clientIDGeneratorOriginal), true);
    }

    private void saveAndReload() {
        cfg.setPluginParameter("web.token", token);
        cfg.setPluginParameter("web.port", port);
        cfg.setPluginParameter("bt.useClientIdModifier", useClientIdModifier);
        try {
            this.cfg.save();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        reloadPlugin();
    }

    private void reloadPlugin() {
        if (webContainer != null) {
            webContainer.stop();
        }
        webContainer = new JavalinWebContainer();
        webContainer.start("0.0.0.0",
                port,
                token);
        log.info("PBH-Adapter WebServer started with: port={}, token={}",
                port,
                token);
        initEndpoints(webContainer.javalin());
    }

    private void initEndpoints(Javalin javalin) {
        javalin.get("/metadata", this::handleMetadata)
                .post("/setconnector", this::handleSetConnector)
                .get("/statistics", this::handleStatistics)
                .get("/downloads", this::handleDownloads)
                .get("/download/{infoHash}", this::handleDownload)
                .get("/download/{infoHash}/peers", this::handlePeers)
                .get("/bans", this::handleBans)
                .post("/bans", this::handleBanListApplied)
                .put("/bans", this::handleBanListReplacement)
                .delete("/bans", this::handleBatchUnban);
    }

    private void handleSetConnector(Context context) {
        this.connectorData = context.bodyAsClass(ConnectorData.class);
    }

    private void handleStatistics(Context context) {
        var stats = pluginInterface.getDownloadManager().getStats();
        context.json(
                new StatisticsRecord(
                        stats.getOverallDataBytesReceived(),
                        stats.getOverallDataBytesSent(),
                        stats.getSessionUptimeSeconds(),
                        stats.getDataReceiveRate(),
                        stats.getProtocolReceiveRate(),
                        stats.getDataAndProtocolReceiveRate(),
                        stats.getDataSendRate(),
                        stats.getProtocolSendRate(),
                        stats.getDataAndProtocolSendRate(),
                        stats.getDataBytesReceived(),
                        stats.getProtocolBytesReceived(),
                        stats.getDataBytesSent(),
                        stats.getProtocolBytesSent(),
                        stats.getSmoothedReceiveRate(),
                        stats.getSmoothedSendRate()
                )
        );
    }

    private void handleMetadata(Context context) {
        MetadataCallbackBean callbackBean = new MetadataCallbackBean(
                pluginInterface.getPluginVersion(),
                pluginInterface.getApplicationVersion(),
                pluginInterface.getApplicationName(),
                pluginInterface.getAzureusName());
        context.json(callbackBean);
    }

    private void handleBanListApplied(Context context) throws IPFilterException {
        BanBean banBean = context.bodyAsClass(BanBean.class);
        IPFilter ipFilter = pluginInterface.getIPFilter();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        runIPFilterOperation(() -> {
            for (String s : banBean.getIps()) {
                try {
                    ipFilter.ban(s, PBH_IDENTIFIER);
                    success.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                    failed.incrementAndGet();
                }
            }
        });
        cleanupPeers(banBean.getIps());
        context.status(HttpStatus.OK);
        context.json(new BatchOperationCallbackBean(success.get(), failed.get()));
    }

    public ConnectorData getConnectorData() {
        if (useClientIdModifier) {
            return connectorData;
        } else {
            return null;
        }
    }

    private void handleDownloads(Context ctx) {
        List<DownloadRecord> records = new ArrayList<>();
        List<Integer> filter = ctx.queryParams("filter").stream().map(Integer::parseInt).collect(Collectors.toList());
        for (Download download : pluginInterface.getDownloadManager().getDownloads()) {
            boolean shouldAddToResultSet = filter.isEmpty() || filter.contains(download.getState());
            if (shouldAddToResultSet) {
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

    private void handleBatchUnban(Context ctx) throws IPFilterException {
        UnBanBean banBean = ctx.bodyAsClass(UnBanBean.class);
        AtomicInteger unbanned = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        runIPFilterOperation(() -> {
            for (String ip : banBean.getIps()) {
                try {
                    pluginInterface.getIPFilter().unban(ip);
                    unbanned.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                    failed.incrementAndGet();
                }
            }
        });
        ctx.status(HttpStatus.OK);
        ctx.json(new BatchOperationCallbackBean(unbanned.get(), failed.get()));
    }

    public void handleDownload(Context ctx) {
        String arg = ctx.pathParam("infoHash");
        byte[] infoHash = hexToByteArray(arg);
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
        byte[] infoHash = hexToByteArray(arg);
        try {
            Download download = pluginInterface.getDownloadManager().getDownload(infoHash);
            if (download == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            if (download.getPeerManager() == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.json(getPeerManagerRecord(download.getPeerManager()));
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    public void handleBanListReplacement(Context ctx) throws IPFilterException {
        BanListReplacementBean replacementBean = ctx.bodyAsClass(BanListReplacementBean.class);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        runIPFilterOperation(() -> {
            IPFilter ipFilter = pluginInterface.getIPFilter();
            for (IPBanned blockedIP : ipFilter.getBannedIPs()) {
                if (PBH_IDENTIFIER.equals(blockedIP.getBannedTorrentName()) || replacementBean.isIncludeNonPBHEntries()) {
                    ipFilter.unban(blockedIP.getBannedIP());
                }
            }
            for (String s : replacementBean.getReplaceWith()) {
                try {
                    ipFilter.ban(s, PBH_IDENTIFIER);
                    success.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                    failed.incrementAndGet();
                }
            }
        });
        cleanupPeers(replacementBean.getReplaceWith());
        ctx.status(HttpStatus.OK);
        ctx.json(new BatchOperationCallbackBean(success.get(), failed.get()));
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
                download.getDownloadPeerId() == null ? null : bytesToHex(download.getDownloadPeerId()).toLowerCase(Locale.ROOT),
                download.isRemoved());
    }

    private PeerManagerRecord getPeerManagerRecord(PeerManager peerManager) {
        if (peerManager == null) return null;
        return new PeerManagerRecord(
                Arrays.stream(peerManager.getPeers()).map(this::getPeerRecord).filter(Objects::nonNull).collect(Collectors.toList()),
                Arrays.stream(peerManager.getPendingPeers()).map(this::getDescriptorRecord).filter(Objects::nonNull).collect(Collectors.toList()),
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
        String client = peer.getClient();
        if (peer instanceof PeerImpl) {
            client = ((PeerImpl) peer).getDelegate().getClientNameFromExtensionHandshake();
        }
        if (peer.getIp().endsWith(".i2p") || peer.getIp().endsWith(".onion") || peer.getIp().endsWith(".tor"))
            return null;
        com.biglybt.pif.messaging.Message[] messages = new Message[0];
        try {
            messages = peer.getSupportedMessages();
        } catch (NullPointerException ignored) {
        }
        return new PeerRecord(
                peer.isMyPeer(),
                peer.getState(),
                peer.getId() == null ? null : bytesToHex(peer.getId()),
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
                Arrays.stream(messages).map(Message::getID).collect(Collectors.toList())
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
