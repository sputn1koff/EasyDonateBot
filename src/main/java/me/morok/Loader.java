package me.morok;

import me.morok.config.Configs;
import me.morok.config.YamlConfigLoader;
import me.morok.easydonate.EasyDonateFacade;
import me.morok.easydonate.PaymentsPoller;
import me.morok.easydonate.PollStateStore;
import me.morok.notify.CompositeNotifier;
import me.morok.notify.TelegramNotifier;
import me.morok.notify.VkNotifier;
import me.morok.recipients.TelegramRecipientsStore;
import me.morok.recipients.VkRecipientsStore;
import me.morok.ui.PaymentFormatter;
import me.morok.ui.TelegramKeyboardFactory;
import me.morok.ui.VkKeyboardFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class Loader {

    static String BRAND = "EasyDonateBot";
    static String AUTHOR = "morok";

    public static void main(String[] args) {
        printlnLine();
        println(BRAND + " запускается...");
        println("Разработчик: " + AUTHOR);
        printlnLine();

        mkdirs("data");
        mkdirs("data/state");
        mkdirs("data/recipients");

        YamlConfigLoader loader = new YamlConfigLoader();
        loader.ensureDefaultConfigs();

        if (loader.wasInstalledNow()) {
            println("Похоже, это первый запуск.");
            println("Файлы конфигурации созданы рядом с jar.");
            println("Бот автоматически отключён, чтобы ты спокойно всё настроил.");
            println("");
            println("Что сделать:");
            println("1) Открой и настрой vk.yml и telegram.yml");
            println("2) Укажи shop-key, токены и включи нужные платформы (enabled: true)");
            println("3) Запусти бота ещё раз");
            printlnLine();
            return;
        }

        Configs configs = loader.load();

        boolean tgEnabled = configs.telegram != null && configs.telegram.enabled;
        boolean vkEnabled = configs.vk != null && configs.vk.enabled;

        println("Статус модулей:");
        println("  Telegram: " + onOff(tgEnabled));
        println("  VK:       " + onOff(vkEnabled));
        printlnLine();

        if (!tgEnabled && !vkEnabled) {
            println("Ни один модуль не включён (enabled: false).");
            println("Бот остановлен. Включи хотя бы один модуль и запусти снова.");
            printlnLine();
            return;
        }

        PollStateStore state = new PollStateStore(Path.of("data/state/last-payment.json"));
        state.load();

        if (configs.easydonateShopKey == null || configs.easydonateShopKey.isBlank()) {
            println("Не найден shop-key (easydonate.shop-key).");
            println("Бот остановлен. Заполни telegram.yml и запусти снова.");
            printlnLine();
            return;
        }

        EasyDonateFacade api = new EasyDonateFacade(configs.easydonateShopKey);
        PaymentFormatter formatter = new PaymentFormatter(configs);

        TelegramRecipientsStore tgRecipients = null;
        VkRecipientsStore vkRecipients = null;

        TelegramNotifier telegram = null;
        VkNotifier vk = null;

        if (tgEnabled) {
            if (configs.telegram.token == null || configs.telegram.token.isBlank()) {
                println("Telegram включён, но token пустой.");
                println("Бот остановлен. Заполни telegram.yml и запусти снова.");
                printlnLine();
                return;
            }

            tgRecipients = new TelegramRecipientsStore(Path.of(configs.telegram.storage.recipientsFile));
            tgRecipients.load();

            telegram = new TelegramNotifier(
                    configs.telegram,
                    tgRecipients,
                    api,
                    formatter,
                    new TelegramKeyboardFactory(configs.telegram)
            );
        }

        if (vkEnabled) {
            if (configs.vk.groupId <= 0) {
                println("VK включён, но group-id некорректный (должен быть > 0).");
                println("Бот остановлен. Заполни vk.yml и запусти снова.");
                printlnLine();
                return;
            }
            if (configs.vk.token == null || configs.vk.token.isBlank()) {
                println("VK включён, но token пустой.");
                println("Бот остановлен. Заполни vk.yml и запусти снова.");
                printlnLine();
                return;
            }

            vkRecipients = new VkRecipientsStore(Path.of(configs.vk.storage.recipientsFile));
            vkRecipients.load();

            vk = new VkNotifier(
                    configs.vk,
                    vkRecipients,
                    api,
                    formatter,
                    new VkKeyboardFactory(configs.vk)
            );
        }

        CompositeNotifier notify = new CompositeNotifier(telegram, vk);

        PaymentsPoller poller = new PaymentsPoller(
                configs.pollIntervalMs,
                configs.lastPaymentsLimit,
                api,
                state,
                notify
        );

        notify.start();
        poller.start();

        println("Готово. Бот запущен.");
        if (tgEnabled) println("Telegram: открой бота и отправь /start");
        if (vkEnabled) println("VK: напиши в группу \"start\" или нажми кнопку в диалоге");
        printlnLine();

        TelegramRecipientsStore finalTgRecipients = tgRecipients;
        VkRecipientsStore finalVkRecipients = vkRecipients;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            println("Остановка...");
            poller.stop();
            notify.stop();
            state.save();

            if (finalTgRecipients != null) finalTgRecipients.save();
            if (finalVkRecipients != null) finalVkRecipients.save();

            println("Остановлено.");
        }));

        CountDownLatch latch = new CountDownLatch(1);
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
    }

    static String onOff(boolean on) {
        return on ? "включен" : "выключен";
    }

    static void mkdirs(String path) {
        try {
            Files.createDirectories(Path.of(path));
        } catch (Exception e) {
            throw new RuntimeException("Не удалось создать директорию: " + path, e);
        }
    }

    static void println(String s) {
        System.out.println("[" + BRAND + "] " + s);
    }

    static void printlnLine() {
        System.out.println("[" + BRAND + "] ----------------------------------------");
    }
}
