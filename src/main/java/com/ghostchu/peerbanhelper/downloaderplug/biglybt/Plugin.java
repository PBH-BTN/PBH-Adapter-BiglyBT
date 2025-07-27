package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.config.ConfigKeys;
import com.biglybt.core.ipfilter.IpFilterExternalHandler;
import com.biglybt.core.ipfilter.IpFilterManagerFactory;
import com.biglybt.core.networkmanager.Transport;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.clientid.ClientIDGenerator;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadException;
import com.biglybt.pif.ipfilter.IPBanned;
import com.biglybt.pif.ipfilter.IPFilter;
import com.biglybt.pif.ipfilter.IPFilterException;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.torrent.TorrentAnnounceURLListSet;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.LabelParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pifimpl.local.clientid.ClientIDManagerImpl;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.ConnectorData;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.clientbound.*;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.serverbound.BatchOperationCallbackBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.serverbound.CurrentSpeedLimiterBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.serverbound.MetadataCallbackBean;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper.DownloadRecord;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper.StatisticsRecord;
import com.ghostchu.peerbanhelper.downloaderplug.biglybt.util.ByteUtil;
import com.google.gson.Gson;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.format.util.DualIPv4v6Tries;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.biglybt.core.config.ConfigKeys.Connection.BCFG_LISTEN_PORT_RANDOMIZE_ENABLE;
import static com.biglybt.core.config.ConfigKeys.Connection.ICFG_TCP_LISTEN_PORT;
import static com.biglybt.core.config.ConfigKeys.Transfer.ICFG_MAX_DOWNLOAD_SPEED_KBS;
import static com.biglybt.core.config.ConfigKeys.Transfer.ICFG_MAX_UPLOAD_SPEED_KBS;

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
    private final DualIPv4v6Tries banList = new DualIPv4v6Tries();
    private final AtomicLong connectionBlockCounter = new AtomicLong(0);
    private LabelParameter connectionBlockCounterLabel;

    @Override
    public void unload() {
        if (webContainer != null) {
            webContainer.stop();
        }
        if (clientIDGeneratorOriginal != null) {
            ClientIDManagerImpl.getSingleton().setGenerator(clientIDGeneratorOriginal, true);
        }
        Arrays.stream(pluginInterface.getDownloadManager().getDownloads()).forEach(download -> {
            PeerManager peerManager = download.getPeerManager();
            if (peerManager != null) {
                Arrays.stream(peerManager.getPeers()).forEach(Plugin::removePBHRateLimiter);
            }
        });
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
        connectionBlockCounterLabel = configModel.addLabelParameter2("peerbanhelper.connectionBlockCounter");
        updateCounterLabel();
        saveAndReload();
        clientIDGeneratorOriginal = ClientIDManagerImpl.getSingleton().getGenerator();
        IpFilterManagerFactory.getSingleton().getIPFilter().addExternalHandler(new IpFilterExternalHandler() {
            @Override
            public boolean isBlocked(byte[] bytes, String s) {
                try {
                    if (banList.elementsContaining(new IPAddressString(s).getAddress()) != null) {
                        connectionBlockCounter.incrementAndGet();
                        updateCounterLabel();
                        return true;
                    }
                } catch (Exception ignored) {
                }
                return false;
            }

            @Override
            public boolean isBlocked(byte[] bytes, InetAddress inetAddress) {
                try {
                    if (banList.elementsContaining(new IPAddressString(inetAddress.getHostAddress()).getAddress()) != null) {
                        connectionBlockCounter.incrementAndGet();
                        updateCounterLabel();
                        return true;
                    }
                } catch (Exception ignored) {
                }
                return false;
            }
        });
        ClientIDManagerImpl.getSingleton().setGenerator(new PBHClientIDGenerator(this, clientIDGeneratorOriginal), true);
    }

    private void updateCounterLabel() {
        connectionBlockCounterLabel.setLabelText("Blocked " + connectionBlockCounter.get() + " connect attempts since last restart.");
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
        COConfigurationManager.setParameter(ConfigKeys.Transfer.BCFG_ALLOW_SAME_IP_PEERS, true);
        initEndpoints(webContainer.javalin());
    }

    private void initEndpoints(Javalin javalin) {
        javalin.get("/metadata", this::handleMetadata)
                .post("/setconnector", this::handleSetConnector)
                .get("/statistics", this::handleStatistics)
                .get("/downloads", this::handleDownloads)
                .get("/download/{infoHash}", this::handleDownload)
                .get("/download/{infoHash}/peers", this::handlePeers)
                .patch("/download/{infoHash}/trackers", this::handleTrackersSet)
                .get("/bans", this::handleBans)
                .post("/bans", this::handleBanListApplied)
                .put("/bans", this::handleBanListReplacement)
                .delete("/bans", this::handleBatchUnban)
                .post("/download/{infoHash}/peer/{ip}/throttling", this::handlePeerThrottling)
                .post("/resetThrottling", this::handleResetThrottling)
                .get("/speedlimiter", this::handleSpeedLimiter)
                .post("/speedlimiter", this::handleSetSpeedLimiter)
                .get("/listenport", this::handleGetListenPort)
                .post("/listenport", this::handleSetListenPort);
    }

    private void handleSetListenPort(@NotNull Context context) {
        SetListenPort bean = context.bodyAsClass(SetListenPort.class);
        COConfigurationManager.setParameter(BCFG_LISTEN_PORT_RANDOMIZE_ENABLE, false);
        COConfigurationManager.setParameter(ICFG_TCP_LISTEN_PORT, bean.getPort());
    }

    private void handleGetListenPort(@NotNull Context context) {
        int port = COConfigurationManager.getIntParameter(ICFG_TCP_LISTEN_PORT);
        context.json(new SetListenPort(port));
    }

    private void handleSetSpeedLimiter(@NotNull Context context) {
        SetSpeedLimiterBean bean = context.bodyAsClass(SetSpeedLimiterBean.class);
        long uploadBytesPerSec = Math.max(bean.getUpload(), 0);
        long downloadBytesPerSec = Math.max(bean.getDownload(), 0);
        COConfigurationManager.setParameter(ICFG_MAX_DOWNLOAD_SPEED_KBS, downloadBytesPerSec / 1024);
        COConfigurationManager.setParameter(ICFG_MAX_UPLOAD_SPEED_KBS, uploadBytesPerSec / 1024);
    }

    private void handleSpeedLimiter(@NotNull Context context) {
        long uploadBytesPerSec = COConfigurationManager.getIntParameter(ICFG_MAX_UPLOAD_SPEED_KBS) * 1024;
        long downloadBytesPerSec = COConfigurationManager.getIntParameter(ICFG_MAX_DOWNLOAD_SPEED_KBS) * 1024;
        context.json(new CurrentSpeedLimiterBean(uploadBytesPerSec, downloadBytesPerSec));
    }

    private void handleResetThrottling(Context context) {
        Arrays.stream(pluginInterface.getDownloadManager().getDownloads()).forEach(download -> {
            PeerManager peerManager = download.getPeerManager();
            if (peerManager != null) {
                Arrays.stream(peerManager.getPeers()).forEach(Plugin::removePBHRateLimiter);
            }
        });
        context.status(HttpStatus.OK);
    }

    private void handlePeerThrottling(Context ctx) {
        String arg = ctx.pathParam("infoHash");
        String ip = ctx.pathParam("ip");
        PeerThrottlingBean bean = ctx.bodyAsClass(PeerThrottlingBean.class);
        byte[] infoHash = ByteUtil.hexToByteArray(arg);
        try {
            Download download = pluginInterface.getDownloadManager().getDownload(infoHash);
            Peer peer = null;
            for (Peer p : download.getPeerManager().getPeers()) {
                if (ip.equals(p.getIp())) {
                    peer = p;
                    break;
                }
            }
            if (peer == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            // BiglyBT 的 API 这也太炸裂了
            if (bean.getUploadRate() == -1 && bean.getDownloadRate() == -1) {
                removePBHRateLimiter(peer);
            }
            if (bean.getUploadRate() != -1 || bean.getDownloadRate() != -1) {
                setPBHRateLimiter(peer, bean.getUploadRate(), bean.getDownloadRate());
            }
            ctx.status(HttpStatus.OK);
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    private void handleTrackersSet(Context ctx) {
        String arg = ctx.pathParam("infoHash");
        byte[] infoHash = ByteUtil.hexToByteArray(arg);
        String trackers = ctx.body();
        try {
            Download download = pluginInterface.getDownloadManager().getDownload(infoHash);
            if (download == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            List<TorrentAnnounceURLListSet> sets = new ArrayList<>();
            for (String tracker : trackers.split("\n\n")) {
                String[] group = tracker.split("\n");
                URL[] urls = new URL[group.length];
                for (int i = 0; i < group.length; i++) {
                    try {
                        urls[i] = new URL(group[i]);
                    } catch (MalformedURLException ignored) {
                    }
                }
                sets.add(download.getTorrent().getAnnounceURLList().create(urls));
            }
            download.getTorrent().getAnnounceURLList().setSets(sets.toArray(new TorrentAnnounceURLListSet[0]));
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
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
        for (String s : banBean.getIps()) {
            try {
                banList.add(new IPAddressString(s).getAddress());
                success.incrementAndGet();
            } catch (Exception e) {
                log.error("Failed to apply banlist for IP: {}", s, e);
                failed.incrementAndGet();
            }
        }
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
                var d = DataConverter.getDownloadRecord(download);
                if (d != null) {
                    records.add(d);
                }
            }
        }
        ctx.status(HttpStatus.OK);
        ctx.json(records);
    }

    public void handleBans(Context ctx) {
        boolean includeNonPBH = Boolean.parseBoolean(ctx.queryParam("includeNonPBH"));
        List<String> banned = new ArrayList<>();
        banList.nodeIterator(false).forEachRemaining(node -> banned.add(node.toString()));
        if (includeNonPBH) {
            for (IPBanned bannedIP : pluginInterface.getIPFilter().getBannedIPs()) {
                banned.add(bannedIP.getBannedIP());
            }
        }
        ctx.status(HttpStatus.OK);
        ctx.json(banned);
    }

    private void handleBatchUnban(Context ctx) throws IPFilterException {
        UnBanBean banBean = ctx.bodyAsClass(UnBanBean.class);
        AtomicInteger unbanned = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (String ip : banBean.getIps()) {
            try {
                banList.remove(new IPAddressString(ip).getAddress());
                unbanned.incrementAndGet();
            } catch (Exception e) {
                log.error("Failed to unban IP: {}", ip, e);
                failed.incrementAndGet();
            }
        }
        ctx.status(HttpStatus.OK);
        ctx.json(new BatchOperationCallbackBean(unbanned.get(), failed.get()));
    }

    public void handleDownload(Context ctx) {
        String arg = ctx.pathParam("infoHash");
        byte[] infoHash = ByteUtil.hexToByteArray(arg);
        try {
            Download download = pluginInterface.getDownloadManager().getDownload(infoHash);
            if (download == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.status(HttpStatus.OK);
            ctx.json(DataConverter.getDownloadRecord(download));
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    public void handlePeers(Context ctx) {
        String arg = ctx.pathParam("infoHash");
        byte[] infoHash = ByteUtil.hexToByteArray(arg);
        try {
            Download download = pluginInterface.getDownloadManager().getDownload(infoHash);
            if (download == null || download.getPeerManager() == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.json(DataConverter.getPeerManagerRecord(download.getPeerManager()));
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    public void handleBanListReplacement(Context ctx) throws IPFilterException {
        BanListReplacementBean replacementBean = ctx.bodyAsClass(BanListReplacementBean.class);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        banList.getIPv4Trie().clear();
        banList.getIPv6Trie().clear();
        for (String s : replacementBean.getReplaceWith()) {
            try {
                banList.add(new IPAddressString(s).getAddress());
                success.incrementAndGet();
            } catch (Exception e) {
                log.error("Failed to apply banlist for IP: {}", s, e);
                failed.incrementAndGet();
            }
        }
        cleanupPeers(replacementBean.getReplaceWith());
        ctx.status(HttpStatus.OK);
        ctx.json(new BatchOperationCallbackBean(success.get(), failed.get()));
    }

    private void cleanupPeers(List<String> peers) {
        Arrays.stream(pluginInterface.getDownloadManager().getDownloads())
                .filter(d -> d.getState() == Download.ST_DOWNLOADING || d.getState() == Download.ST_SEEDING).forEach(download -> {
                    PeerManager peerManager = download.getPeerManager();
                    if (peerManager != null) {
                        peers.forEach(ip -> {
                            Peer[] waitToBan = peerManager.getPeers(ip);
                            if (waitToBan != null) {
                                for (Peer peer : waitToBan) {
                                    peerManager.removePeer(peer, PBH_IDENTIFIER, Transport.CR_IP_BLOCKED);
                                    connectionBlockCounter.incrementAndGet();
                                }
                            }
                        });
                    }
                });
    }

    public static void removePBHRateLimiter(Peer peer) {
        PeerRateLimiterLookupResult rateLimiterLookupResult = getPBHRateLimiter(peer);
        if (rateLimiterLookupResult.getUploadLimiter() != null) {
            peer.removeRateLimiter(rateLimiterLookupResult.getUploadLimiter(), true);
            log.info("Removed upload rate limit for peer {}", peer.getIp());
        }
        if (rateLimiterLookupResult.getDownloadLimiter() != null) {
            peer.removeRateLimiter(rateLimiterLookupResult.getDownloadLimiter(), false);
            log.info("Removed download rate limit for peer {}", peer.getIp());
        }
    }

    public static void setPBHRateLimiter(Peer peer, int uploadRate, int downloadRate) {
        removePBHRateLimiter(peer);
        if (uploadRate != -1) {
            PBHRateLimiter uploadLimiter = new PBHRateLimiter(uploadRate);
            peer.addRateLimiter(uploadLimiter, true);
            log.info("Set upload rate limit for peer {} to {}", peer.getIp(), uploadRate);
        }
        if (downloadRate != -1) {
            PBHRateLimiter downloadLimiter = new PBHRateLimiter(downloadRate);
            peer.addRateLimiter(downloadLimiter, false);
            log.info("Set download rate limit for peer {} to {}", peer.getIp(), downloadRate);
        }
    }

    public static PeerRateLimiterLookupResult getPBHRateLimiter(Peer peer) {
        Optional<PBHRateLimiter> uploadLimiter = Arrays.stream(peer.getRateLimiters(true))
                .filter(rateLimiter -> rateLimiter instanceof PBHRateLimiter)
                .map(rateLimiter -> (PBHRateLimiter) rateLimiter).findAny();
        Optional<PBHRateLimiter> downloadLimiter = Arrays.stream(peer.getRateLimiters(false))
                .filter(rateLimiter -> rateLimiter instanceof PBHRateLimiter)
                .map(rateLimiter -> (PBHRateLimiter) rateLimiter).findAny();
        return new PeerRateLimiterLookupResult(uploadLimiter.orElse(null), downloadLimiter.orElse(null));
    }


    @Data
    @AllArgsConstructor
    public static class PeerRateLimiterLookupResult {
        @Nullable
        private final PBHRateLimiter uploadLimiter;
        @Nullable
        private final PBHRateLimiter downloadLimiter;
    }


}
