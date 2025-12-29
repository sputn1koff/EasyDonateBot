package me.morok.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.morok.config.VkConfig;
import me.morok.easydonate.EasyDonateFacade;
import me.morok.recipients.PagerStateStore;
import me.morok.recipients.VkRecipientsStore;
import me.morok.ui.PaymentFormatter;
import me.morok.ui.VkKeyboardFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VkNotifier implements Notifier {

    static String API = "https://api.vk.com/method/";
    static String VERSION = "5.199";

    static String BTN_PREV = "⬅️";
    static String BTN_NEXT = "➡️";

    VkConfig config;
    VkRecipientsStore recipients;

    EasyDonateFacade api;
    PaymentFormatter fmt;
    VkKeyboardFactory keys;

    PagerStateStore pages;

    ObjectMapper json = new ObjectMapper();
    HttpClient http;

    ScheduledExecutorService exec;

    volatile boolean running;

    volatile String lpServer;
    volatile String lpKey;
    volatile String lpTs;

    Random rnd = new Random();

    public VkNotifier(VkConfig config, VkRecipientsStore recipients, EasyDonateFacade api, PaymentFormatter fmt, VkKeyboardFactory keys) {
        this.config = config;
        this.recipients = recipients;
        this.api = api;
        this.fmt = fmt;
        this.keys = keys;

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        if (config != null && config.storage != null && config.storage.stateFile != null && !config.storage.stateFile.isBlank()) {
            pages = new PagerStateStore(java.nio.file.Path.of(config.storage.stateFile));
            pages.load();
        } else {
            pages = new PagerStateStore(null);
        }
    }

    @Override
    public void start() {
        if (config == null) return;
        if (!config.enabled) return;

        if (config.token == null || config.token.isBlank()) throw new IllegalStateException("vk token пустой");
        if (config.groupId <= 0) throw new IllegalStateException("vk group-id должен быть > 0");

        if (running) return;
        running = true;

        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VkNotifier");
            t.setDaemon(true);
            return t;
        });

        exec.scheduleAtFixedRate(this::longPollSafe, 200, 700, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (exec != null) exec.shutdownNow();

        if (pages != null) pages.save();
    }

    @Override
    public void notifyNewPayment(EasyDonateFacade.Payment payment) {
        if (config == null) return;
        if (!config.enabled) return;

        List<Long> targets = recipientsForBroadcast();
        if (targets.isEmpty()) return;

        String text = fmt.formatVkNewPayment(payment);

        for (Long userId : targets) {
            sendMessage(userId, text, keys.lastPaymentsKeyboard());
        }
    }

    void longPollSafe() {
        try {
            longPoll();
        } catch (Exception ignored) {
        }
    }

    void longPoll() {
        if (!running) return;

        if (lpServer == null || lpKey == null || lpTs == null) {
            if (!refreshLongPollServer()) return;
        }

        int wait = Math.max(5, config.longpoll.waitSec);

        String url = lpServer + "?act=a_check&key=" + enc(lpKey) + "&ts=" + enc(lpTs) + "&wait=" + wait;

        JsonNode root = getJson(url);
        if (root == null) {
            refreshLongPollServer();
            return;
        }

        if (root.has("failed")) {
            refreshLongPollServer();
            return;
        }

        String ts = root.path("ts").asText(null);
        if (ts != null) lpTs = ts;

        JsonNode updates = root.path("updates");
        if (!updates.isArray()) return;

        for (JsonNode upd : updates) {
            String type = upd.path("type").asText("");
            if ("message_new".equals(type)) handleMessageNew(upd.path("object").path("message"));
        }
    }

    void handleMessageNew(JsonNode msg) {
        long userId = msg.path("from_id").asLong(0);
        if (userId <= 0) return;

        String text = msg.path("text").asText("");
        if (text == null) text = "";
        text = text.trim();

        recipients.add(userId);
        recipients.save();

        if (text.equalsIgnoreCase("start") || text.equalsIgnoreCase("/start")) {
            pages.set(userId, 1);
            pages.save();
            sendMessage(userId, config.messages.start, keys.lastPaymentsKeyboard());
            return;
        }

        if (text.equalsIgnoreCase(config.ui.buttonLastPayments) || text.equalsIgnoreCase("Последние покупки")) {
            pages.set(userId, 1);
            pages.save();
            sendLastPayments(userId, 1);
            return;
        }

        if (text.equalsIgnoreCase(BTN_PREV)) {
            int cur = pages.get(userId);
            sendLastPayments(userId, cur - 1);
            return;
        }

        if (text.equalsIgnoreCase(BTN_NEXT)) {
            int cur = pages.get(userId);
            sendLastPayments(userId, cur + 1);
            return;
        }
    }

    void sendLastPayments(long userId, int page) {
        List<EasyDonateFacade.Payment> last = api.getLastPayments(config.lastPaymentsLimit);

        if (last == null || last.isEmpty()) {
            sendMessage(userId, config.messages.noPayments, keys.lastPaymentsKeyboard());
            return;
        }

        int pagesCount = fmt.pages(last.size());
        if (pagesCount < 1) pagesCount = 1;

        if (page < 1) page = 1;
        if (page > pagesCount) page = pagesCount;

        pages.set(userId, page);
        pages.save();

        String text = fmt.formatVkLastPaymentsPage(last, page);

        ObjectNode kb;
        if (pagesCount <= 1) kb = keys.lastPaymentsKeyboard();
        else kb = keys.pagerKeyboard(page, pagesCount);

        sendMessage(userId, text, kb);
    }

    boolean refreshLongPollServer() {
        String url = API + "groups.getLongPollServer"
                + "?group_id=" + config.groupId
                + "&access_token=" + enc(config.token)
                + "&v=" + VERSION;

        JsonNode root = getJson(url);
        if (root == null) return false;

        JsonNode resp = root.path("response");
        String server = resp.path("server").asText(null);
        String key = resp.path("key").asText(null);
        String ts = resp.path("ts").asText(null);

        if (server == null || key == null || ts == null) return false;

        lpServer = server;
        lpKey = key;
        lpTs = ts;

        return true;
    }

    void sendMessage(long userId, String text, ObjectNode keyboard) {
        try {
            if (keyboard == null && keys != null) keyboard = keys.lastPaymentsKeyboard();

            String url = API + "messages.send";

            StringBuilder sb = new StringBuilder();
            sb.append("user_id=").append(userId);
            sb.append("&random_id=").append(rnd.nextInt(Integer.MAX_VALUE));
            sb.append("&message=").append(enc(text));
            sb.append("&access_token=").append(enc(config.token));
            sb.append("&v=").append(VERSION);

            if (keyboard != null) sb.append("&keyboard=").append(enc(keyboard.toString()));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                    .build();

            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    JsonNode getJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
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
        if (config.adminUserIds != null && !config.adminUserIds.isEmpty()) return config.adminUserIds;
        return recipients.all();
    }

    String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
