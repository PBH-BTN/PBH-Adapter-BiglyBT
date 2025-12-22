package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Data
@NoArgsConstructor
public class StatisticsRecord {
    private Long overallDataBytesReceived;
    private Long overallDataBytesSent;
    private Long sessionUptimeSeconds;
    private Long dataReceiveRate;
    private Long protocolReceiveRate;
    private Long dataAndProtocolReceiveRate;
    private Long dataSendRate;
    private Long protocolSendRate;
    private Long dataAndProtocolSendRate;
    private Long dataBytesReceived;
    private Long protocolBytesReceived;
    private Long dataBytesSent;
    private Long protocolBytesSent;
    private Long smoothedReceiveRate;
    private Long smoothedSendRate;
}
