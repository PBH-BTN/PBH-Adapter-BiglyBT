package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.serverbound;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CurrentSpeedLimiterBean {
    private long upload;
    private long download;
}
