package me.morok.util;

public class RateLimitGuard {

    long minIntervalMs;

    long nextAt;

    public RateLimitGuard(long minIntervalMs) {
        this.minIntervalMs = Math.max(0, minIntervalMs);
    }

    public synchronized void acquire() {
        long now = System.currentTimeMillis();
        if (now < nextAt) {
            long sleep = nextAt - now;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                }
            }
        }

        nextAt = System.currentTimeMillis() + minIntervalMs;
    }
}
