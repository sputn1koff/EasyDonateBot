package me.morok.config;

public class Configs {

    public String easydonateShopKey;

    public long pollIntervalMs;
    public int lastPaymentsLimit;

    public TelegramConfig telegram;
    public VkConfig vk;

    public Configs() {
        telegram = new TelegramConfig();
        vk = new VkConfig();
    }
}
