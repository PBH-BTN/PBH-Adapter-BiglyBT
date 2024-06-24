package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class SupportedMessageRecord {
    private String id;
    private int type;
    private String description;

}
