package com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.wrapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
/*
com.biglybt.pif.torrent.Torrent.java
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TorrentRecord {
    private String name;
    private String hashBase64;
    private long size;
    private long creationDate;
    private String createdBy;
    private long pieceSize;
    private long pieceCount;
    private boolean decentralized;
    private boolean privateTorrent;
    private boolean complete;
}
