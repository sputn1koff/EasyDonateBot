package me.morok.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class YamlConfigLoader {

    volatile boolean installedNow;

    public boolean wasInstalledNow() {
        return installedNow;
    }

    public void ensureDefaultConfigs() {
        boolean a = ensureConfigFile("telegram.yml", "telegram.yml", defaultTelegramTemplate());
        boolean b = ensureConfigFile("vk.yml", "vk.yml", defaultVkTemplate());
        installedNow = a || b;
    }

    public Configs load() {
        Configs out = new Configs();

        Map<String, Object> tg = loadYamlPreferFile("telegram.yml");
        Map<String, Object> vk = loadYamlPreferFile("vk.yml");

        out.easydonateShopKey = str(get(tg, "easydonate.shop-key"));

        out.pollIntervalMs = longVal(get(tg, "polling.interval-ms"), 3000);
        out.lastPaymentsLimit = intVal(get(tg, "polling.last-payments-limit"), 10);

        applyTelegram(out.telegram, tg, out);
        applyVk(out.vk, vk, out);

        return out;
    }

    void applyTelegram(TelegramConfig c, Map<String, Object> src, Configs root) {
        c.enabled = bool(get(src, "enabled"), true);

        c.token = str(get(src, "bot.token"));
        c.parseMode = str(get(src, "bot.parse-mode"), "HTML");

        c.adminChatIds = longList(get(src, "bot.admin-chat-ids"));

        c.pollingIntervalMs = longVal(get(src, "polling.interval-ms"), root.pollIntervalMs);
        c.lastPaymentsLimit = intVal(get(src, "polling.last-payments-limit"), root.lastPaymentsLimit);

        c.storage.recipientsFile = str(get(src, "storage.recipients-file"), "data/recipients/telegram.json");
        c.storage.stateFile = str(get(src, "storage.state-file"), "data/state/last-payment.json");

        c.ui.buttonLastPayments = str(get(src, "ui.button-last-payments"), "🧾 Последние покупки");
        c.ui.titleNewPayment = str(get(src, "ui.title-new-payment"), "🛒 Новая покупка");
        c.ui.titleLastPayments = str(get(src, "ui.title-last-payments"), "🧾 Последние покупки");

        c.messages.start = str(get(src, "messages.start"), "Привет! Я бот уведомлений EasyDonate.");
        c.messages.noPayments = str(get(src, "messages.no-payments"), "Покупок пока нет.");
    }

    void applyVk(VkConfig c, Map<String, Object> src, Configs root) {
        c.enabled = bool(get(src, "enabled"), false);

        c.groupId = longVal(get(src, "vk.group-id"), 0);
        c.token = str(get(src, "vk.token"));

        c.adminUserIds = longList(get(src, "vk.admin-user-ids"));

        c.longpoll.waitSec = intVal(get(src, "longpoll.wait-sec"), 25);

        c.pollingIntervalMs = longVal(get(src, "polling.interval-ms"), root.pollIntervalMs);
        c.lastPaymentsLimit = intVal(get(src, "polling.last-payments-limit"), root.lastPaymentsLimit);

        c.storage.recipientsFile = str(get(src, "storage.recipients-file"), "data/recipients/vk.json");
        c.storage.stateFile = str(get(src, "storage.state-file"), "data/state/last-payment.json");

        c.ui.buttonLastPayments = str(get(src, "ui.button-last-payments"), "🧾 Последние покупки");
        c.ui.titleNewPayment = str(get(src, "ui.title-new-payment"), "🛒 Новая покупка");
        c.ui.titleLastPayments = str(get(src, "ui.title-last-payments"), "🧾 Последние покупки");

        c.messages.start = str(get(src, "messages.start"), "Привет! Я бот уведомлений EasyDonate.");
        c.messages.noPayments = str(get(src, "messages.no-payments"), "Покупок пока нет.");
    }

    boolean ensureConfigFile(String fileName, String resourceName, String fallbackText) {
        try {
            Path fs = Path.of(fileName);
            if (Files.exists(fs)) return false;

            byte[] bytes = null;

            InputStream in = ClassLoader.getSystemResourceAsStream(resourceName);
            if (in != null) {
                try (InputStream closeMe = in) {
                    bytes = closeMe.readAllBytes();
                }
            }

            if (bytes == null || bytes.length == 0) {
                bytes = fallbackText.getBytes(StandardCharsets.UTF_8);
            }

            Files.write(fs, bytes);
            return true;

        } catch (Exception e) {
            throw new RuntimeException("Не удалось создать " + fileName + ": " + e.getMessage(), e);
        }
    }

    Map<String, Object> loadYamlPreferFile(String name) {
        try {
            Yaml yaml = new Yaml();

            Path fs = Path.of(name);
            if (Files.exists(fs)) {
                Object obj = yaml.load(Files.readString(fs));
                if (!(obj instanceof Map)) return Map.of();
                return (Map<String, Object>) obj;
            }

            InputStream in = ClassLoader.getSystemResourceAsStream(name);
            if (in == null) throw new IllegalStateException("Не найден конфиг: " + name + " (ни рядом с jar, ни в resources)");

            Object obj = yaml.load(in);
            if (!(obj instanceof Map)) return Map.of();
            return (Map<String, Object>) obj;

        } catch (Exception e) {
            throw new RuntimeException("Ошибка чтения " + name + ": " + e.getMessage(), e);
        }
    }

    String defaultTelegramTemplate() {
        return ""
                + "enabled: true\n"
                + "\n"
                + "easydonate:\n"
                + "  shop-key: \"\"\n"
                + "\n"
                + "bot:\n"
                + "  token: \"\"\n"
                + "  parse-mode: \"HTML\"\n"
                + "  admin-chat-ids: []\n"
                + "\n"
                + "polling:\n"
                + "  interval-ms: 3000\n"
                + "  last-payments-limit: 10\n"
                + "\n"
                + "storage:\n"
                + "  recipients-file: \"data/recipients/telegram.json\"\n"
                + "  state-file: \"data/state/last-payment.json\"\n"
                + "\n"
                + "ui:\n"
                + "  button-last-payments: \"🧾 Последние покупки\"\n"
                + "  title-new-payment: \"🛒 Новая покупка\"\n"
                + "  title-last-payments: \"🧾 Последние покупки\"\n"
                + "\n"
                + "messages:\n"
                + "  start: \"Привет! Я бот уведомлений EasyDonate.\"\n"
                + "  no-payments: \"Покупок пока нет.\"\n";
    }

    String defaultVkTemplate() {
        return ""
                + "enabled: false\n"
                + "\n"
                + "vk:\n"
                + "  group-id: 0\n"
                + "  token: \"\"\n"
                + "  admin-user-ids: []\n"
                + "\n"
                + "longpoll:\n"
                + "  wait-sec: 25\n"
                + "\n"
                + "polling:\n"
                + "  interval-ms: 3000\n"
                + "  last-payments-limit: 10\n"
                + "\n"
                + "storage:\n"
                + "  recipients-file: \"data/recipients/vk.json\"\n"
                + "  state-file: \"data/state/last-payment.json\"\n"
                + "\n"
                + "ui:\n"
                + "  button-last-payments: \"🧾 Последние покупки\"\n"
                + "  title-new-payment: \"🛒 Новая покупка\"\n"
                + "  title-last-payments: \"🧾 Последние покупки\"\n"
                + "\n"
                + "messages:\n"
                + "  start: \"Привет! Я бот уведомлений EasyDonate.\"\n"
                + "  no-payments: \"Покупок пока нет.\"\n";
    }

    Object get(Map<String, Object> root, String path) {
        if (root == null) return null;

        String[] parts = path.split("\\.");
        Object cur = root;

        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(p);
            if (cur == null) return null;
        }

        return cur;
    }

    String str(Object o) {
        if (o == null) return null;
        return String.valueOf(o);
    }

    String str(Object o, String def) {
        String v = str(o);
        if (v == null || v.isBlank()) return def;
        return v;
    }

    boolean bool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return (boolean) o;
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        return def;
    }

    int intVal(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    long longVal(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    List<Long> longList(Object o) {
        List<Long> out = new ArrayList<>();
        if (o == null) return out;

        if (o instanceof List) {
            for (Object it : (List<?>) o) {
                Long v = toLong(it);
                if (v != null) out.add(v);
            }
            return out;
        }

        Long single = toLong(o);
        if (single != null) out.add(single);
        return out;
    }

    Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
