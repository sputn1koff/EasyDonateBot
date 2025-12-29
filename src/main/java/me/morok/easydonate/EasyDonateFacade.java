package me.morok.easydonate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.morok.util.RateLimitGuard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EasyDonateFacade {

    static String BASE = "https://easydonate.ru";

    ObjectMapper json = new ObjectMapper();
    HttpClient http;

    String shopKey;

    RateLimitGuard rps = new RateLimitGuard(1100);

    public EasyDonateFacade(String shopKey) {
        this.shopKey = shopKey;
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<Payment> getLastPayments(int limit) {
        if (limit <= 0) return List.of();

        int paginate = 50;
        int pages = (limit + paginate - 1) / paginate;
        if (pages < 1) pages = 1;
        if (pages > 20) pages = 20; // защита от ебанутых конфигов

        Map<Long, Payment> uniq = new LinkedHashMap<>();

        for (int page = 1; page <= pages; page++) {
            List<Payment> chunk = getShopPaymentsPage(paginate, page);
            if (chunk.isEmpty()) break;

            for (Payment p : chunk) {
                if (p == null || p.id <= 0) continue;
                uniq.put(p.id, p);
            }

            if (chunk.size() < paginate) break; // дальше платежей уже нет
        }

        if (uniq.isEmpty()) {
            // fallback на старый метод если вдруг руки из жопы
            List<Payment> plugin = getPluginLastPayments(limit);
            if (plugin.isEmpty()) return List.of();
            plugin.sort(Comparator.comparingLong(a -> a.id));
            if (plugin.size() <= limit) return plugin;
            return plugin.subList(plugin.size() - limit, plugin.size());
        }

        List<Payment> out = new ArrayList<>(uniq.values());
        out.sort(Comparator.comparingLong(a -> a.id));

        if (out.size() <= limit) return out;
        return out.subList(out.size() - limit, out.size());
    }

    List<Payment> getShopPaymentsPage(int paginate, int page) {
        if (paginate < 1) paginate = 50;
        if (paginate > 50) paginate = 50;
        if (page < 1) page = 1;

        JsonNode root = get("/api/v3/shop/payments?paginate=" + paginate + "&page=" + page);
        if (root == null) return List.of();
        if (!root.path("success").asBoolean(false)) return List.of();

        JsonNode resp = root.path("response");

        JsonNode arr = null;
        if (resp.isArray()) arr = resp;
        else if (resp.isObject() && resp.path("data").isArray()) arr = resp.path("data");

        if (arr == null || !arr.isArray()) return List.of();

        List<Payment> out = new ArrayList<>();
        for (JsonNode p : arr) {
            Payment pay = parsePayment(p);
            if (pay != null) out.add(pay);
        }

        return out;
    }

    List<Payment> getPluginLastPayments(int limit) {
        if (limit <= 0) return List.of();

        JsonNode root = get("/api/v3/plugin/EasyDonate.LastPayments/getPayments");
        if (root == null) return List.of();
        if (!root.path("success").asBoolean(false)) return List.of();

        JsonNode resp = root.path("response");
        if (!resp.isArray()) return List.of();

        List<Payment> out = new ArrayList<>();

        for (JsonNode p : resp) {
            Payment pay = parsePayment(p);
            if (pay != null) out.add(pay);
        }

        out.sort(Comparator.comparingLong(a -> a.id));
        if (out.size() <= limit) return out;
        return out.subList(out.size() - limit, out.size());
    }

    JsonNode get(String path) {
        if (shopKey == null || shopKey.isBlank()) throw new IllegalStateException("easydonate.shop-key пустой");

        rps.acquire();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Shop-Key", shopKey)
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;

            return json.readTree(res.body());
        } catch (Exception e) {
            return null;
        }
    }

    Payment parsePayment(JsonNode p) {
        if (p == null || p.isObject() == false) return null;

        Payment out = new Payment();

        out.id = p.path("id").asLong(0);
        out.customer = text(p, "customer");
        out.email = text(p, "email");

        out.status = p.path("status").asInt(0);

        out.paymentSystem = text(p, "payment_system");
        out.paymentType = text(p, "payment_type");

        out.createdAt = text(p, "created_at");
        out.serverName = p.path("server").path("name").asText(null);

        out.test = boolAny(p, "test", "is_test", "isTest", "test_payment");
        if (!out.test) {
            if (out.paymentType != null && out.paymentType.equalsIgnoreCase("test")) out.test = true;
            if (out.paymentSystem != null && out.paymentSystem.equalsIgnoreCase("test")) out.test = true;
        }

        out.sentCommands = new ArrayList<>();
        JsonNode sc = p.path("sent_commands");
        if (sc.isArray()) {
            for (JsonNode x : sc) {
                if (x == null || !x.isObject()) continue;
                SentCommand c = new SentCommand();
                c.command = text(x, "command");
                c.response = text(x, "response");
                if (c.command != null || c.response != null) out.sentCommands.add(c);
            }
        }

        out.products = new ArrayList<>();
        JsonNode products = p.path("products");
        if (products.isArray()) {
            for (JsonNode pr : products) {
                Product x = parseProduct(pr);
                if (x != null) out.products.add(x);
            }
        }

        out.totalDiscount = 0;
        for (Product pr : out.products) out.totalDiscount += pr.discountAmount;

        return out;
    }

    Product parseProduct(JsonNode pr) {
        if (pr == null || pr.isObject() == false) return null;

        Product out = new Product();

        out.name = text(pr, "name");
        out.type = text(pr, "type");

        out.price = pr.path("price").asDouble(0);
        out.totalCost = pr.path("total_cost").asDouble(0);

        JsonNode old = pr.get("old_price");
        if (old != null && old.isNull() == false) out.oldPrice = old.asDouble(0);
        else out.oldPrice = null;

        out.amount = pr.path("amount").asInt(1);
        out.number = pr.path("number").asInt(1);

        out.discountAmount = 0;
        if (out.oldPrice != null && out.oldPrice > out.price) out.discountAmount = out.oldPrice - out.price;

        JsonNode sales = pr.path("sales");
        if (sales.isObject()) {
            JsonNode mass = sales.path("massSale");
            if (mass.isObject()) {
                out.massSaleValue = mass.path("value").asDouble(0);
                out.massSaleDescription = text(mass, "description");

                JsonNode target = mass.path("target");
                if (target.isObject()) {
                    out.massSaleName = text(target, "name");
                    out.massSalePercent = target.path("sale").asInt(0);
                }
            }

            out.coupon = text(sales, "coupon");
            out.promocode = text(sales, "promocode");
        }

        if (out.coupon == null) out.coupon = text(pr, "coupon");
        if (out.promocode == null) out.promocode = text(pr, "promocode");
        if (out.promocode == null) out.promocode = text(pr, "promo");

        if (out.totalCost < 0) out.totalCost = 0;

        return out;
    }

    String text(JsonNode node, String key) {
        if (node == null) return null;
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        if (s == null || s.isBlank()) return null;
        return s;
    }

    boolean boolAny(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v == null || v.isNull()) continue;
            if (v.isBoolean()) return v.asBoolean();
            String s = v.asText("").trim().toLowerCase();
            if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        }
        return false;
    }

    public static class Payment {
        public long id;

        public String customer;
        public String email;

        public String serverName;

        public int status;

        public String paymentSystem;
        public String paymentType;
        public String createdAt;

        public boolean test;

        public double totalDiscount;

        public List<SentCommand> sentCommands = Collections.emptyList();
        public List<Product> products = Collections.emptyList();
    }

    public static class SentCommand {
        public String command;
        public String response;
    }

    public static class Product {
        public String name;
        public String type;

        public double price;
        public double totalCost;
        public Double oldPrice;

        public int amount;
        public int number;

        public double discountAmount;

        public double massSaleValue;
        public String massSaleDescription;
        public String massSaleName;
        public int massSalePercent;

        public String coupon;
        public String promocode;
    }
}
