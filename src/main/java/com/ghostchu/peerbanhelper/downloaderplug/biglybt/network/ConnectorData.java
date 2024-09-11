package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConnectorData {
    private String software;
    private String version;
    private String abbrev;
}
