package me.morok.notify;

import me.morok.easydonate.EasyDonateFacade;

public interface Notifier {

    void start();

    void stop();

    void notifyNewPayment(EasyDonateFacade.Payment payment);
}
