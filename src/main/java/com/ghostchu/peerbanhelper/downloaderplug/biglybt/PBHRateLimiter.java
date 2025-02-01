package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.biglybt.pif.network.RateLimiter;

public class PBHRateLimiter implements RateLimiter {
    private int rateLimit;

    public PBHRateLimiter(int rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public String getName() {
        return "PeerBanHelper";
    }

    @Override
    public int getRateLimitBytesPerSecond() {
        return rateLimit;
    }

    @Override
    public void setRateLimitBytesPerSecond(int i) {
        this.rateLimit = i;
    }

    @Override
    public long getRateLimitTotalByteCount() {
        return -1;
    }
}
