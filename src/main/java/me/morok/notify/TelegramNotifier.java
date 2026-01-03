package me.morok.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.morok.config.TelegramConfig;
import me.morok.easydonate.EasyDonateFacade;
import me.morok.recipients.TelegramRecipientsStore;
import me.morok.ui.PaymentFormatter;
import me.morok.ui.TelegramKeyboardFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TelegramNotifier implements Notifier {

    TelegramConfig config;
    EasyDonateFacade api;
    PaymentFormatter fmt;

    ObjectMapper json = new ObjectMapper();
    HttpClient http;

    ScheduledExecutorService exec;

    volatile boolean running;
    AtomicLong offset = new AtomicLong(0);

    public TelegramNotifier(TelegramConfig config, TelegramRecipientsStore recipients, EasyDonateFacade api, PaymentFormatter fmt, TelegramKeyboardFactory keys) {
        this.config = config;
        this.api = api;
        this.fmt = fmt;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void start() {
        if (config == null || !config.enabled) return;
        if (config.token == null || config.token.isBlank()) throw new IllegalStateException("telegram bot.token пустой");

        if (running) return;
        running = true;

        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TelegramNotifier");
            t.setDaemon(true);
            return t;
        });

        long poll = Math.max(800, config.pollingIntervalMs);
        exec.scheduleAtFixedRate(this::updatesSafe, 200, poll, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (exec != null) exec.shutdownNow();
    }

    @Override
    public void notifyNewPayment(EasyDonateFacade.Payment payment) {
        if (config == null || !config.enabled) return;

        List<Long> targets = config.adminChatIds;
        if (targets == null || targets.isEmpty()) return;

        String text = fmt.formatTelegramNewPayment(payment, false);

        for (Long chatId : targets) {
            sendMessage(chatId, text);
        }
    }

    void updatesSafe() {
        try {
            updates();
        } catch (Exception ignored) {}
    }

    void updates() {
        if (!running) return;
        String url = "https://api.telegram.org/bot" + config.token + "/getUpdates?timeout=0";
        long off = offset.get();
        if (off > 0) url += "&offset=" + off;

        JsonNode root = getJson(url);
        if (root == null || !root.path("ok").asBoolean(false)) return;

        JsonNode res = root.path("result");
        if (!res.isArray()) return;

        for (JsonNode upd : res) {
            long updId = upd.path("update_id").asLong(0);
            if (updId > 0) offset.accumulateAndGet(updId + 1, Math::max);
        }
    }

    void sendMessage(long chatId, String text) {
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            if (config.parseMode != null && !config.parseMode.isBlank()) payload.put("parse_mode", config.parseMode);
            payload.put("disable_web_page_preview", true);

            String url = "https://api.telegram.org/bot" + config.token + "/sendMessage";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    JsonNode getJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            return json.readTree(res.body());
        } catch (Exception e) { return null; }
    }
}