package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.config.ConfigKeys;
import com.biglybt.core.ipfilter.IpFilterExternalHandler;
import com.biglybt.core.ipfilter.IpFilterManagerFactory;
import com.biglybt.core.networkmanager.Transport;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.PluginListener;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.clientid.ClientIDGenerator;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadException;
import com.biglybt.pif.ipfilter.IPBanned;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.torrent.TorrentAnnounceURLListSet;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.LabelParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.utils.UTTimer;
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
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.ServiceUnavailableResponse;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.biglybt.core.config.ConfigKeys.Connection.BCFG_LISTEN_PORT_RANDOMIZE_ENABLE;
import static com.biglybt.core.config.ConfigKeys.Connection.ICFG_TCP_LISTEN_PORT;
import static com.biglybt.core.config.ConfigKeys.Transfer.*;

@Slf4j
@Getter
public class Plugin implements UnloadablePlugin {
    public static final Gson GSON = new Gson();
    private static final String PBH_IDENTIFIER = "<PeerBanHelper>";
    private PluginInterface pluginInterface;
    private IntParameter listenPortParam;
    private StringParameter accessKeyParam;
    private BasicPluginConfigModel configModel;
    private JavalinWebContainer webContainer;
    private StringParameter bindAddressParam;
    private ApiSettings activeSettings;
    private boolean restoringSettings;
    private PluginConfig cfg;
    private volatile ConnectorData connectorData;
    private ClientIDGenerator clientIDGeneratorOriginal;
    private ClientIDGenerator installedGenerator;
    private volatile boolean useClientIdModifier;
    private BooleanParameter clientIdModifier;
    private final BanState bans = new BanState();
    private IpFilterExternalHandler filterHandler;
    private final PluginListener lifecycle = new PluginListener() {
        @Override
        public void initializationComplete() {}

        @Override
        public void closedownInitiated() {
            unload();
        }

        @Override
        public void closedownComplete() {}
    };
    private final AtomicLong connectionBlockCounter = new AtomicLong(0);
    private volatile LabelParameter connectionBlockCounterLabel;
    private UTTimer counterTimer;
    private final Semaphore snapshotSlots = new Semaphore(2);

    @Override
    public synchronized void unload() {
        pluginInterface.removeListener(lifecycle);
        if (counterTimer != null) {
            counterTimer.destroy();
            counterTimer = null;
        }
        connectionBlockCounterLabel = null;
        if (filterHandler != null) {
            IpFilterManagerFactory.getSingleton().getIPFilter().removeExternalHandler(filterHandler);
            filterHandler = null;
        }
        if (webContainer != null) {
            webContainer.stop();
            webContainer = null;
        }
        if (clientIDGeneratorOriginal != null) {
            if (ClientIDManagerImpl.getSingleton().getGenerator() == installedGenerator) {
                ClientIDManagerImpl.getSingleton().setGenerator(clientIDGeneratorOriginal, true);
            }
        }
        Arrays.stream(pluginInterface.getDownloadManager().getDownloads()).forEach(download -> {
            PeerManager peerManager = download.getPeerManager();
            if (peerManager != null) {
                Arrays.stream(peerManager.getPeers()).forEach(Plugin::removePBHRateLimiter);
            }
        });
        if (configModel != null) {
            configModel.destroy();
            configModel = null;
        }
    }

    @Override
    public synchronized void initialize(PluginInterface pluginInterface) {
        this.pluginInterface = pluginInterface;
        this.cfg = pluginInterface.getPluginconfig();
        boolean existingInstall = !cfg.getPluginStringParameter("web.token", "").isBlank();
        var settings = new ApiSettings(
                cfg.getPluginStringParameter("web.bind", existingInstall ? "0.0.0.0" : "127.0.0.1"),
                cfg.getPluginIntParameter("web.port", 7759),
                cfg.getPluginStringParameter("web.token", UUID.randomUUID().toString()));
        this.useClientIdModifier = cfg.getPluginBooleanParameter("bt.useClientIdModifier", false);
        configModel = pluginInterface.getUIManager().createBasicPluginConfigModel("peerbanhelper.configui");
        listenPortParam = configModel.addIntParameter2("api-port", "peerbanhelper.port", settings.port);
        accessKeyParam = configModel.addStringParameter2("api-token", "peerbanhelper.token", settings.token);
        bindAddressParam = configModel.addStringParameter2("api-bind", "peerbanhelper.bind", settings.host);
        listenPortParam.addListener(parameter -> reloadEditedSettings());
        accessKeyParam.addListener(parameter -> reloadEditedSettings());
        bindAddressParam.addListener(parameter -> reloadEditedSettings());
        clientIdModifier = configModel.addBooleanParameter2("use-client-id-modifier", "peerbanhelper.clientIdModifier", useClientIdModifier);
        clientIdModifier.addListener(lis -> {
            this.useClientIdModifier = clientIdModifier.getValue();
            updateClientIdGenerator();
            cfg.setPluginParameter("bt.useClientIdModifier", this.useClientIdModifier);
        });
        connectionBlockCounterLabel = configModel.addLabelParameter2("peerbanhelper.connectionBlockCounter");
        updateCounterLabel();
        clientIDGeneratorOriginal = ClientIDManagerImpl.getSingleton().getGenerator();
        filterHandler = new IpFilterExternalHandler() {
            @Override
            public boolean isBlocked(byte[] bytes, String s) {
                return isBlocked(s);
            }

            @Override
            public boolean isBlocked(byte[] hash, InetAddress address) {
                return isBlocked(address.getHostAddress());
            }

            private boolean isBlocked(String address) {
                if (!bans.matches(address)) {
                    return false;
                }
                connectionBlockCounter.incrementAndGet();
                return true;
            }
        };
        installedGenerator = new PBHClientIDGenerator(this, clientIDGeneratorOriginal);
        try {
            IpFilterManagerFactory.getSingleton().getIPFilter().addExternalHandler(filterHandler);
            updateClientIdGenerator();
            saveAndReload(settings);
            showSettings(settings);
            counterTimer = pluginInterface.getUtilities().createTimer("PeerBanHelper counter", true);
            counterTimer.addPeriodicEvent(2000, event -> updateCounterLabel());
            pluginInterface.addListener(lifecycle);
        } catch (RuntimeException e) {
            unload();
            throw e;
        }
    }

    private void updateClientIdGenerator() {
        if (installedGenerator == null) {
            return;
        }
        var manager = ClientIDManagerImpl.getSingleton();
        if (useClientIdModifier && manager.getGenerator() == clientIDGeneratorOriginal) {
            manager.setGenerator(installedGenerator, true);
        } else if (!useClientIdModifier && manager.getGenerator() == installedGenerator) {
            manager.setGenerator(clientIDGeneratorOriginal, true);
        }
    }

    private void updateCounterLabel() {
        LabelParameter label = connectionBlockCounterLabel;
        if (label != null) {
            label.setLabelText("Blocked " + connectionBlockCounter.get() + " connections since plugin startup.");
        }
    }

    private synchronized void reloadEditedSettings() {
        if (restoringSettings || configModel == null || activeSettings == null) {
            return;
        }
        var candidate = new ApiSettings(bindAddressParam.getValue().trim(), listenPortParam.getValue(),
                accessKeyParam.getValue());
        try {
            saveAndReload(candidate);
        } catch (RuntimeException e) {
            showSettings(activeSettings);
            log.warn("Could not apply PeerBanHelper API settings", e);
        }
    }

    private void showSettings(ApiSettings settings) {
        restoringSettings = true;
        try {
            listenPortParam.setValue(settings.port);
            bindAddressParam.setValue(settings.host);
            accessKeyParam.setValue(settings.token);
        } finally {
            restoringSettings = false;
        }
    }

    private void saveAndReload(ApiSettings settings) {
        if (settings.port < 1 || settings.port > 65535 || settings.host.isBlank() || settings.token.isBlank()) {
            throw new IllegalArgumentException("Invalid API bind address, port or token");
        }
        JavalinWebContainer previous = webContainer;
        var next = new JavalinWebContainer();
        if (previous != null) {
            previous.stop();
        }
        try {
            next.start(settings.host, settings.port, settings.token, this::initEndpoints);
        } catch (RuntimeException failure) {
            if (previous != null) {
                try {
                    previous.start(activeSettings.host, activeSettings.port, activeSettings.token, this::initEndpoints);
                } catch (RuntimeException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
            }
            throw failure;
        }
        webContainer = next;
        activeSettings = settings;
        cfg.setPluginParameter("web.bind", settings.host);
        cfg.setPluginParameter("web.token", settings.token);
        cfg.setPluginParameter("web.port", settings.port);
        try {
            cfg.save();
        } catch (Exception e) {
            log.warn("Could not save PeerBanHelper adapter settings", e);
        }
        log.info("PeerBanHelper API listening on {}:{}", settings.host, settings.port);
    }

    private static final class ApiSettings {
        final String host;
        final int port;
        final String token;

        ApiSettings(String host, int port, String token) {
            this.host = host;
            this.port = port;
            this.token = token;
        }
    }

    private void initEndpoints(Javalin javalin) {
        javalin.get("/metadata", this::handleMetadata)
                .post("/setconnector", this::handleSetConnector)
                .get("/statistics", this::handleStatistics)
                .get("/v2/bans", ctx -> ctx.json(banStatus()))
                .put("/v2/bans", ctx -> updateBans(ctx, true))
                .patch("/v2/bans", ctx -> updateBans(ctx, false))
                .get("/v2/snapshot", this::handleSnapshot)
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
        COConfigurationManager.setParameter(LCFG_MAX_DOWNLOAD_SPEED_KBS, downloadBytesPerSec / 1024);
        COConfigurationManager.setParameter(LCFG_MAX_UPLOAD_SPEED_KBS, uploadBytesPerSec / 1024);
    }

    private void handleSpeedLimiter(@NotNull Context context) {
        long uploadBytesPerSec = COConfigurationManager.getIntParameter(LCFG_MAX_UPLOAD_SPEED_KBS) * 1024L;
        long downloadBytesPerSec = COConfigurationManager.getIntParameter(LCFG_MAX_DOWNLOAD_SPEED_KBS) * 1024L;
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
                pluginInterface.getAzureusName(), 2);
        context.json(callbackBean);
    }

    private void handleBanListApplied(Context context) {
        applyLegacyBans(context, context.bodyAsClass(BanBean.class).getIps(), false, false);
    }

    private Map<String, Object> banStatus() {
        return Map.of("instance", bans.instance(), "revision", bans.revision(),
                "filterEnabled", IpFilterManagerFactory.getSingleton().getIPFilter().isEnabled());
    }

    private void updateBans(Context context, boolean replace) {
        BanUpdate request = context.bodyAsClass(BanUpdate.class);
        if (request == null) {
            throw new BadRequestResponse("Expected a ban update");
        }
        synchronized (bans) {
            long baseRevision = -1;
            if (!replace) {
                if (!bans.instance().equals(request.instance) || request.revision == null
                        || request.revision != bans.revision()) {
                    throw new ConflictResponse("The ban list must be synchronized again");
                }
                baseRevision = request.revision;
            }
            applyBans(replace, request.added, replace ? List.of() : request.removed, baseRevision);
            if (replace || !request.added.isEmpty()) {
                cleanupPeers();
            }
            context.json(banStatus());
        }
    }

    private void applyLegacyBans(Context context, List<String> addresses, boolean replace, boolean remove) {
        if (addresses == null) {
            throw new BadRequestResponse("Missing IP address list");
        }
        synchronized (bans) {
            int success = bans.updateLegacy(replace, remove, addresses);
            if (!remove && success > 0) {
                cleanupPeers();
            }
            context.json(new BatchOperationCallbackBean(success, addresses.size() - success));
        }
    }

    private void applyBans(boolean replace, List<String> added, List<String> removed, long revision) {
        try {
            bans.update(replace, added, removed, revision);
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse(e.getMessage());
        }
    }

    private static final class BanUpdate {
        String instance;
        Long revision;
        List<String> added;
        List<String> removed;
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
        banned.addAll(bans.addresses());
        if (includeNonPBH) {
            for (IPBanned bannedIP : pluginInterface.getIPFilter().getBannedIPs()) {
                banned.add(bannedIP.getBannedIP());
            }
        }
        ctx.status(HttpStatus.OK);
        ctx.json(banned);
    }

    private void handleBatchUnban(Context ctx) {
        applyLegacyBans(ctx, ctx.bodyAsClass(UnBanBean.class).getIps(), false, true);
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
            var record = DataConverter.getPeerManagerRecord(download.getPeerManager());
            if (record == null) {
                ctx.json(Map.of("peers", List.of()));
                return;
            }
            ctx.json(record);
        } catch (DownloadException e) {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    public void handleBanListReplacement(Context ctx) {
        applyLegacyBans(ctx, ctx.bodyAsClass(BanListReplacementBean.class).getReplaceWith(), true, false);
    }

    private void cleanupPeers() {
        var filter = IpFilterManagerFactory.getSingleton().getIPFilter();
        if (!filter.isEnabled()) {
            return;
        }
        for (Download download : pluginInterface.getDownloadManager().getDownloads()) {
            PeerManager manager = download.getPeerManager();
            var torrent = download.getTorrent();
            if (manager == null || torrent == null) {
                continue;
            }
            byte[] hash = torrent.getHash();
            for (Peer peer : manager.getPeers()) {
                String ip = peer.getIp();
                if (bans.matches(ip) && filter.isInRange(ip, download.getName(), hash, false, false)) {
                    manager.removePeer(peer, PBH_IDENTIFIER, Transport.CR_IP_BLOCKED);
                }
            }
        }
    }

    private void handleSnapshot(Context ctx) {
        if (!snapshotSlots.tryAcquire()) {
            throw new ServiceUnavailableResponse("BiglyBT is already collecting peer snapshots");
        }
        try {
            ctx.contentType("application/json").result(collectSnapshot(
                    !"false".equals(ctx.queryParam("includePrivate"))));
        } finally {
            snapshotSlots.release();
        }
    }

    private String collectSnapshot(boolean includePrivate) {
        var snapshot = new SnapshotResponse();
        for (Download download : pluginInterface.getDownloadManager().getDownloads()) {
            int state = download.getState();
            if (state != Download.ST_DOWNLOADING && state != Download.ST_SEEDING) {
                continue;
            }
            var record = DataConverter.getDownloadRecord(download);
            if (record == null || (!includePrivate && record.getTorrent().isPrivateTorrent())) {
                continue;
            }
            snapshot.add(Map.of("download", record, "peers", DataConverter.getPeers(download.getPeerManager())));
        }
        return snapshot.finish();
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
