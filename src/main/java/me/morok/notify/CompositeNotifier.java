package me.morok.notify;

import me.morok.easydonate.EasyDonateFacade;

public class CompositeNotifier implements Notifier {

    TelegramNotifier telegram;
    VkNotifier vk;

    public CompositeNotifier(TelegramNotifier telegram, VkNotifier vk) {
        this.telegram = telegram;
        this.vk = vk;
    }

    @Override
    public void start() {
        if (telegram != null) telegram.start();
        if (vk != null) vk.start();
    }

    @Override
    public void stop() {
        if (telegram != null) telegram.stop();
        if (vk != null) vk.stop();
    }

    @Override
    public void notifyNewPayment(EasyDonateFacade.Payment payment) {
        if (telegram != null) telegram.notifyNewPayment(payment);
        if (vk != null) vk.notifyNewPayment(payment);
    }
}
