package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.bean.clientbound;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SetSpeedLimiterBean {
    private long upload;
    private long download;
}
