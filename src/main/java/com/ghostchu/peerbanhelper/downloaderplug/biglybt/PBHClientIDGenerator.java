package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.biglybt.pif.clientid.ClientIDException;
import com.biglybt.pif.clientid.ClientIDGenerator;
import lombok.Getter;

import java.util.Objects;
import java.util.Properties;
@Getter
public class PBHClientIDGenerator implements ClientIDGenerator {
    private final static String APPEND_USER_AGENT_TEMPLATE = ";%s/%s (%s)";
    private final static String APPEND_CLIENT_NAME_TEMPLATE = " (🛡%s/%s)";
    private final ClientIDGenerator parent;
    private final Plugin plugin;

    public PBHClientIDGenerator(Plugin plugin, ClientIDGenerator parent) {
        this.plugin = plugin;
        this.parent = parent;
    }

    @Override
    public byte[] generatePeerID(byte[] hash, boolean for_tracker) throws ClientIDException {
        return parent.generatePeerID(hash, for_tracker);
    }

    @Override
    public void generateHTTPProperties(byte[] hash, Properties properties) throws ClientIDException {
        parent.generateHTTPProperties(hash, properties);
        var userAgent = properties.get(ClientIDGenerator.PR_USER_AGENT);
        var connectorData = plugin.getConnectorData();
        if(connectorData == null) return;
        var ourUserAgent = String.format(APPEND_USER_AGENT_TEMPLATE, connectorData.getSoftware(), connectorData.getVersion(), connectorData.getAbbrev());
        userAgent += ourUserAgent;
        properties.put(ClientIDGenerator.PR_USER_AGENT, userAgent);
    }

    @Override
    public String[] filterHTTP(byte[] hash, String[] lines_in) {
        return lines_in;
    }

    @Override
    public Object getProperty(byte[] hash, String property_name) {
        var def = parent.getProperty(hash, property_name);
        if (Objects.equals(property_name, ClientIDGenerator.PR_CLIENT_NAME)) {
            var connectorData = plugin.getConnectorData();
            if(connectorData == null) return def;
            return def + String.format(APPEND_CLIENT_NAME_TEMPLATE, connectorData.getSoftware(), connectorData.getVersion());
        }
        return def;
    }
}
