package me.morok.ui;

import me.morok.config.Configs;
import me.morok.easydonate.EasyDonateFacade;
import me.morok.util.TimeUtil;

import java.util.List;

public class PaymentFormatter {

    static int PAGE_SIZE = 10;

    Configs cfg;

    TimeUtil time = new TimeUtil();

    public PaymentFormatter(Configs cfg) {
        this.cfg = cfg;
    }

    public int pages(int total) {
        if (total <= 0) return 1;
        return (total + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    public String formatTelegramNewPayment(EasyDonateFacade.Payment p) {
        return formatTelegramNewPayment(p, false);
    }

    public String formatTelegramNewPayment(EasyDonateFacade.Payment p, boolean showLogs) {
        StringBuilder sb = new StringBuilder();

        sb.append("<b>").append(escape(cfg.telegram.ui.titleNewPayment)).append("</b>\n\n");
        appendCore(sb, p, true, showLogs);

        return sb.toString();
    }

    public String formatVkNewPayment(EasyDonateFacade.Payment p) {
        return formatVkNewPayment(p, true);
    }

    public String formatVkNewPayment(EasyDonateFacade.Payment p, boolean showLogs) {
        StringBuilder sb = new StringBuilder();

        sb.append(cfg.vk.ui.titleNewPayment).append("\n\n");
        appendCore(sb, p, false, showLogs);

        return sb.toString();
    }

    public String formatTelegramLastPaymentsPage(List<EasyDonateFacade.Payment> list, int page) {
        if (list == null || list.isEmpty()) return escape(cfg.telegram.messages.noPayments);

        int pages = pages(list.size());
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(escape(cfg.telegram.ui.titleLastPayments)).append("</b>\n");
        sb.append("Страница ").append(page).append("/").append(pages).append("\n\n");

        appendListPage(sb, list, page, true);
        return sb.toString();
    }

    public String formatVkLastPaymentsPage(List<EasyDonateFacade.Payment> list, int page) {
        if (list == null || list.isEmpty()) return cfg.vk.messages.noPayments;

        int pages = pages(list.size());
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        StringBuilder sb = new StringBuilder();
        sb.append(cfg.vk.ui.titleLastPayments).append("\n");
        sb.append("Страница ").append(page).append("/").append(pages).append("\n\n");

        appendListPage(sb, list, page, false);
        return sb.toString();
    }

    void appendCore(StringBuilder sb, EasyDonateFacade.Payment p, boolean html, boolean showLogs) {
        String buyer = safe(p.customer, "—");
        String when = time.pretty(p.createdAt);

        if (html) sb.append("Покупатель: <b>").append(escape(buyer)).append("</b>\n");
        else sb.append("Покупатель: ").append(buyer).append("\n");

        if (p.serverName != null && !p.serverName.isBlank()) sb.append("Сервер: ").append(html ? escape(p.serverName) : p.serverName).append("\n");
        if (when != null) sb.append("Дата: ").append(when).append("\n");

        sb.append("Статус: ").append(statusText(p)).append("\n");

        if (p.test) sb.append("Тип: TEST-платёж\n");

        sb.append("\n");
        appendProducts(sb, p, html);

        if (showLogs) appendSentCommands(sb, p, html);
    }

    void appendProducts(StringBuilder sb, EasyDonateFacade.Payment p, boolean html) {
        if (p.products == null || p.products.isEmpty()) {
            sb.append("Товары: —\n");
            return;
        }

        sb.append("Товары:\n");

        int shown = 0;
        for (EasyDonateFacade.Product pr : p.products) {
            if (shown >= 5) break;
            shown++;

            String name = safe(pr.name, "—");
            sb.append("• ").append(html ? escape(name) : name);

            if (pr.amount > 1) sb.append(" x").append(pr.amount);

            sb.append(" — ").append(money(pr.price));

            String disc = discountedHint(pr);
            if (disc != null) sb.append(" ").append(html ? escape(disc) : disc);

            if (pr.oldPrice != null && pr.oldPrice > pr.price) sb.append(" (было ").append(money(pr.oldPrice)).append(")");

            sb.append("\n");

            String promoLine = promoLine(pr);
            if (promoLine != null) sb.append("  ").append(html ? escape(promoLine) : promoLine).append("\n");

            String codeLine = codeLine(pr);
            if (codeLine != null) sb.append("  ").append(html ? escape(codeLine) : codeLine).append("\n");
        }

        if (p.products.size() > shown) sb.append("• …и ещё ").append(p.products.size() - shown).append("\n");

        if (p.totalDiscount > 0.0001) sb.append("\nСкидка: ").append(money(p.totalDiscount)).append("\n");
    }

    void appendSentCommands(StringBuilder sb, EasyDonateFacade.Payment p, boolean html) {
        sb.append("\nЛог сервера:\n");

        if (p == null || p.sentCommands == null || p.sentCommands.isEmpty()) {
            if (p != null && p.status == 1) {
                String t = "Пока нет — магазин ещё обрабатывает платёж/команды не выполнялись.";
                sb.append("• ").append(html ? escape(t) : t).append("\n");
            } else {
                sb.append("• —\n");
            }
            return;
        }

        int shown = 0;
        for (EasyDonateFacade.SentCommand c : p.sentCommands) {
            if (shown >= 5) break;
            shown++;

            String resp = c == null ? null : c.response;
            String cmd = c == null ? null : c.command;

            if (resp != null && !resp.isBlank()) {
                sb.append("• ").append(html ? escape(resp) : resp).append("\n");
                continue;
            }

            if (cmd != null && !cmd.isBlank()) {
                sb.append("• ").append(html ? escape(cmd) : cmd).append("\n");
            }
        }

        if (p.sentCommands.size() > shown) sb.append("• …и ещё ").append(p.sentCommands.size() - shown).append("\n");
    }

    void appendListPage(StringBuilder sb, List<EasyDonateFacade.Payment> list, int page, boolean html) {
        int total = list.size();

        int fromBack = (page - 1) * PAGE_SIZE;
        int toBack = fromBack + PAGE_SIZE;

        int endExclusive = total - fromBack;
        int startInclusive = total - toBack;

        if (endExclusive < 0) endExclusive = 0;
        if (startInclusive < 0) startInclusive = 0;

        int idxOut = 0;
        for (int i = endExclusive - 1; i >= startInclusive; i--) {
            EasyDonateFacade.Payment p = list.get(i);
            idxOut++;

            String buyer = safe(p.customer, "—");
            String when = time.pretty(p.createdAt);
            String server = safe(p.serverName, "—");

            sb.append(idxOut).append(". ");
            sb.append(html ? escape(buyer) : buyer);

            sb.append(" [").append(html ? escape(server) : server).append("]");

            if (p.test) sb.append(" [TEST]");

            if (when != null) sb.append(" — ").append(when);

            sb.append("\n");

            if (p.products != null && !p.products.isEmpty()) {
                EasyDonateFacade.Product pr = p.products.get(0);
                String prName = safe(pr.name, "—");

                sb.append("   ").append(html ? escape(prName) : prName);

                if (pr.amount > 1) sb.append(" x").append(pr.amount);
                sb.append(" — ").append(money(pr.price));

                String disc = discountedHint(pr);
                if (disc != null) sb.append(" ").append(html ? escape(disc) : disc);

                if (p.products.size() > 1) sb.append(" (+").append(p.products.size() - 1).append(")");

                sb.append("\n");
            }

            if (p.totalDiscount > 0.0001) sb.append("   Скидка: ").append(money(p.totalDiscount)).append("\n");

            sb.append("   ").append("Статус: ").append(statusText(p)).append("\n");

            sb.append("\n");
        }
    }

    String discountedHint(EasyDonateFacade.Product pr) {
        if (pr == null) return null;

        double base = pr.price;
        if (base <= 0) return null;

        int amount = pr.amount;
        if (amount < 1) amount = 1;

        double shownTotal = base * amount;

        double real = pr.totalCost;
        if (real <= 0) return null;

        if (amount > 1 && real > 0 && real <= base) {
            double asTotal = real * amount;
            if (asTotal > 0) real = asTotal;
        }

        if (real + 0.0001 >= shownTotal) return null;

        return "(" + money(real) + " с учетом скидки)";
    }

    String statusText(EasyDonateFacade.Payment p) {
        if (p == null) return "—";

        int st = p.status;

        if (st == 2) {
            if (p.test) return "Успешно (TEST)";
            return "Успешно";
        }

        if (st == 1) {
            if (p.test) return "В обработке (TEST)";
            return "В обработке";
        }

        if (st == 3) return "Отменён";
        if (st == 4) return "Возврат средств";

        return "Неуспешно (status=" + st + ")";
    }

    String promoLine(EasyDonateFacade.Product pr) {
        if (pr.massSaleName == null && pr.massSaleDescription == null) return null;

        String name = pr.massSaleName;
        if (name == null || name.isBlank()) name = pr.massSaleDescription;

        if (name == null || name.isBlank()) return null;

        if (pr.massSalePercent > 0) return "Акция: " + name + " (-" + pr.massSalePercent + "%)";
        if (pr.massSaleValue > 0.0001) return "Акция: " + name + " (скидка " + money(pr.massSaleValue) + ")";
        return "Акция: " + name;
    }

    String codeLine(EasyDonateFacade.Product pr) {
        String code = pr.promocode;
        String coupon = pr.coupon;

        if (code != null && !code.isBlank()) return "Промокод: " + code;
        if (coupon != null && !coupon.isBlank()) return "Купон: " + coupon;
        return null;
    }

    String money(double v) {
        String s;
        if (v == (long) v) s = String.valueOf((long) v);
        else s = String.format(java.util.Locale.US, "%.2f", v);

        return s + " ₽";
    }

    String safe(String s, String def) {
        if (s == null || s.isBlank()) return def;
        return s;
    }

    String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
