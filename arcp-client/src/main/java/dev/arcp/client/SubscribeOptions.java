package dev.arcp.client;

public record SubscribeOptions(boolean history, long fromEventSeq) {
    public static SubscribeOptions live() {
        return new SubscribeOptions(false, 0);
    }

    public static SubscribeOptions withHistory(long fromEventSeq) {
        return new SubscribeOptions(true, fromEventSeq);
    }
}
