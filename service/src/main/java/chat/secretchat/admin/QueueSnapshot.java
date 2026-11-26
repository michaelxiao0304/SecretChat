package chat.secretchat.admin;

record QueueSnapshot(long pendingCount, Long oldestWaitSeconds) {
    static QueueSnapshot at(long count, Long oldestMillis, long nowMillis) {
        if (oldestMillis == null) return new QueueSnapshot(count, null);
        long elapsed = Math.max(0L, nowMillis - oldestMillis);
        return new QueueSnapshot(count, elapsed / 1000);
    }
}
