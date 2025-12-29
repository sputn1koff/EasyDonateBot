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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TelegramNotifier implements Notifier {

    static int CACHE_MAX = 512;
    static long CACHE_TTL_MS = 30 * 60 * 1000L;

    TelegramConfig config;
    TelegramRecipientsStore recipients;

    EasyDonateFacade api;
    PaymentFormatter fmt;
    TelegramKeyboardFactory keys;

    ObjectMapper json = new ObjectMapper();
    HttpClient http;

    ScheduledExecutorService exec;

    volatile boolean running;
    AtomicLong offset = new AtomicLong(0);

    ConcurrentHashMap<Long, CachedPayment> payCache = new ConcurrentHashMap<>();
    ConcurrentLinkedQueue<Long> payOrder = new ConcurrentLinkedQueue<>();
    AtomicInteger payPuts = new AtomicInteger(0);

    public TelegramNotifier(TelegramConfig config, TelegramRecipientsStore recipients, EasyDonateFacade api, PaymentFormatter fmt, TelegramKeyboardFactory keys) {
        this.config = config;
        this.recipients = recipients;
        this.api = api;
        this.fmt = fmt;
        this.keys = keys;

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void start() {
        if (config == null) return;
        if (!config.enabled) return;

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
        if (config == null) return;
        if (!config.enabled) return;

        List<Long> targets = recipientsForBroadcast();
        if (targets.isEmpty()) return;

        cachePut(payment);

        String text = fmt.formatTelegramNewPayment(payment, false);
        ObjectNode kb = keys.paymentLogsKeyboard(payment == null ? 0 : payment.id, false);

        for (Long chatId : targets) {
            sendMessage(chatId, text, kb);
        }
    }

    void updatesSafe() {
        try {
            updates();
        } catch (Exception ignored) {
        }
    }

    void updates() {
        if (!running) return;

        String url = "https://api.telegram.org/bot" + config.token + "/getUpdates?timeout=0&allowed_updates=%5B%22message%22,%22callback_query%22%5D";

        long off = offset.get();
        if (off > 0) url += "&offset=" + off;

        JsonNode root = getJson(url);
        if (root == null) return;
        if (!root.path("ok").asBoolean(false)) return;

        JsonNode res = root.path("result");
        if (!res.isArray()) return;

        for (JsonNode upd : res) {
            long updId = upd.path("update_id").asLong(0);
            if (updId > 0) offset.accumulateAndGet(updId + 1, Math::max);

            JsonNode msg = upd.get("message");
            if (msg != null && msg.isObject()) handleMessage(msg);

            JsonNode cb = upd.get("callback_query");
            if (cb != null && cb.isObject()) handleCallback(cb);
        }
    }

    void handleMessage(JsonNode msg) {
        long chatId = msg.path("chat").path("id").asLong(0);
        if (chatId == 0) return;

        String text = msg.path("text").asText("");
        if (text == null) text = "";
        text = text.trim();

        recipients.add(chatId);
        recipients.save();

        if (text.equalsIgnoreCase("/start")) {
            sendMessage(chatId, config.messages.start, keys.inlineLastPaymentsKeyboard());
            return;
        }

        if (text.equalsIgnoreCase(config.ui.buttonLastPayments) || text.equalsIgnoreCase("Последние покупки")) {
            sendLastPayments(chatId, 1, 0);
        }
    }

    void handleCallback(JsonNode cb) {
        String data = cb.path("data").asText("");
        long chatId = cb.path("message").path("chat").path("id").asLong(0);
        int messageId = cb.path("message").path("message_id").asInt(0);
        String cbId = cb.path("id").asText(null);

        if (chatId == 0) return;

        if (data == null) data = "";
        data = data.trim();

        if (data.startsWith("lp:")) {
            String s = data.substring(3).trim();
            if (s.equalsIgnoreCase("noop")) {
                if (cbId != null) answerCallback(cbId, null);
                return;
            }

            int page = parseInt(s, 1);
            sendLastPayments(chatId, page, messageId);

            if (cbId != null) answerCallback(cbId, null);
            return;
        }

        if (data.startsWith("pl:")) {
            handlePaymentLogsCallback(chatId, messageId, data, cbId);
        }
    }

    void handlePaymentLogsCallback(long chatId, int messageId, String data, String cbId) {
        try {
            String[] parts = data.split(":");
            if (parts.length < 3) {
                if (cbId != null) answerCallback(cbId, null);
                return;
            }

            long payId = parseLong(parts[1], 0);
            String act = parts[2].trim().toLowerCase();

            if (payId <= 0 || messageId <= 0) {
                if (cbId != null) answerCallback(cbId, null);
                return;
            }

            boolean show = act.equals("show");

            EasyDonateFacade.Payment p = resolvePayment(payId);
            if (p == null) {
                if (cbId != null) answerCallback(cbId, "Платёж не найден");
                return;
            }

            String text = fmt.formatTelegramNewPayment(p, show);
            ObjectNode kb = keys.paymentLogsKeyboard(payId, show);

            editMessage(chatId, messageId, text, kb);

            if (cbId != null) answerCallback(cbId, null);
        } catch (Exception ignored) {
            if (cbId != null) answerCallback(cbId, null);
        }
    }

    EasyDonateFacade.Payment resolvePayment(long payId) {
        EasyDonateFacade.Payment fresh = findInLastPayments(payId);
        if (fresh != null) {
            cachePut(fresh);
            return fresh;
        }

        CachedPayment cached = payCache.get(payId);
        if (cached == null) return null;

        long now = System.currentTimeMillis();
        if (cached.at + CACHE_TTL_MS < now) {
            payCache.remove(payId);
            return null;
        }

        return cached.p;
    }

    EasyDonateFacade.Payment findInLastPayments(long payId) {
        try {
            int lim = 50;
            if (config != null && config.lastPaymentsLimit > lim) lim = config.lastPaymentsLimit;

            List<EasyDonateFacade.Payment> last = api.getLastPayments(lim);
            if (last == null || last.isEmpty()) return null;

            for (int i = last.size() - 1; i >= 0; i--) {
                EasyDonateFacade.Payment p = last.get(i);
                if (p == null) continue;
                if (p.id == payId) return p;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    void cachePut(EasyDonateFacade.Payment p) {
        if (p == null) return;
        if (p.id <= 0) return;

        long now = System.currentTimeMillis();

        payCache.put(p.id, new CachedPayment(p, now));
        payOrder.add(p.id);

        int n = payPuts.incrementAndGet();
        if (n % 32 != 0) return;

        cacheCleanup(now);
    }

    void cacheCleanup(long now) {
        for (var e : payCache.entrySet()) {
            CachedPayment v = e.getValue();
            if (v == null) continue;
            if (v.at + CACHE_TTL_MS < now) payCache.remove(e.getKey());
        }

        // size лимит
        while (payCache.size() > CACHE_MAX) {
            Long id = payOrder.poll();
            if (id == null) break;

            CachedPayment v = payCache.get(id);
            if (v == null) continue;

            if (v.at + CACHE_TTL_MS < now) payCache.remove(id);
            else {
                payCache.remove(id);
            }
        }
    }

    void sendLastPayments(long chatId, int page, int editMessageId) {
        List<EasyDonateFacade.Payment> last = api.getLastPayments(config.lastPaymentsLimit);

        if (last == null || last.isEmpty()) {
            String no = config.messages.noPayments;
            if (editMessageId > 0) editMessage(chatId, editMessageId, no, null);
            else sendMessage(chatId, no, null);
            return;
        }

        int pages = fmt.pages(last.size());
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        String text = fmt.formatTelegramLastPaymentsPage(last, page);
        ObjectNode pager = pages <= 1 ? null : keys.inlinePagerKeyboard(page, pages);

        if (editMessageId > 0) editMessage(chatId, editMessageId, text, pager);
        else sendMessage(chatId, text, pager);
    }

    void answerCallback(String cbId, String text) {
        try {
            String url = "https://api.telegram.org/bot" + config.token + "/answerCallbackQuery";

            String body = "callback_query_id=" + enc(cbId);
            if (text != null && !text.isBlank()) body += "&text=" + enc(text);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    void editMessage(long chatId, int messageId, String text, ObjectNode replyMarkup) {
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("chat_id", chatId);
            payload.put("message_id", messageId);
            payload.put("text", text);

            if (config.parseMode != null && !config.parseMode.isBlank()) payload.put("parse_mode", config.parseMode);
            payload.put("disable_web_page_preview", true);

            if (replyMarkup != null) payload.set("reply_markup", replyMarkup);

            String url = "https://api.telegram.org/bot" + config.token + "/editMessageText";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    void sendMessage(long chatId, String text, ObjectNode replyMarkup) {
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("chat_id", chatId);
            payload.put("text", text);

            if (config.parseMode != null && !config.parseMode.isBlank()) payload.put("parse_mode", config.parseMode);
            payload.put("disable_web_page_preview", true);

            if (replyMarkup != null) payload.set("reply_markup", replyMarkup);

            String url = "https://api.telegram.org/bot" + config.token + "/sendMessage";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    JsonNode getJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;

            return json.readTree(res.body());
        } catch (Exception e) {
            return null;
        }
    }

    List<Long> recipientsForBroadcast() {
        if (config.adminChatIds != null && !config.adminChatIds.isEmpty()) return config.adminChatIds;
        return recipients.all();
    }

    int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static class CachedPayment {
        EasyDonateFacade.Payment p;
        long at;

        CachedPayment(EasyDonateFacade.Payment p, long at) {
            this.p = p;
            this.at = at;
        }
    }
}
