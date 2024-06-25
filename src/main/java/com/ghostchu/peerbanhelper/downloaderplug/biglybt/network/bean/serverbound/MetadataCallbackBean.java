package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.serverbound;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class MetadataCallbackBean {
    private String pluginVersion;
    private String applicationVersion;
    private String applicationName;
    private String azureusName;
}
