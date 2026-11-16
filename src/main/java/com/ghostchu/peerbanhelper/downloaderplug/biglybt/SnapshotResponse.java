package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import io.javalin.http.HttpResponseException;

import java.io.Writer;

/** Encodes one torrent at a time and rejects oversized responses without returning a partial scan. */
final class SnapshotResponse extends Writer {
    private static final int MAX_CHARS = 16 * 1024 * 1024;
    private final StringBuilder json = new StringBuilder("[");
    private boolean first = true;

    void add(Object torrent) {
        if (!first) {
            json.append(',');
        }
        first = false;
        Plugin.GSON.toJson(torrent, torrent.getClass(), this);
    }

    String finish() {
        return json.append(']').toString();
    }

    @Override
    public void write(char[] chars, int offset, int length) {
        if (length > MAX_CHARS - json.length()) {
            throw new HttpResponseException(507, "BiglyBT peer snapshot exceeds the response limit");
        }
        json.append(chars, offset, length);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
