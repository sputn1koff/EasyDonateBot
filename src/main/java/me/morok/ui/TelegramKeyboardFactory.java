package me.morok.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.morok.config.TelegramConfig;

public class TelegramKeyboardFactory {

    ObjectMapper json = new ObjectMapper();

    TelegramConfig cfg;

    public TelegramKeyboardFactory(TelegramConfig cfg) {
        this.cfg = cfg;
    }

    public ObjectNode inlineLastPaymentsKeyboard() {
        ObjectNode markup = json.createObjectNode();

        ArrayNode keyboard = json.createArrayNode();
        ArrayNode row = json.createArrayNode();

        ObjectNode btn = json.createObjectNode();
        btn.put("text", cfg.ui.buttonLastPayments);

        row.add(btn);
        keyboard.add(row);

        markup.set("keyboard", keyboard);
        markup.put("resize_keyboard", true);
        markup.put("one_time_keyboard", false);
        markup.put("is_persistent", true);

        return markup;
    }

    public ObjectNode inlinePagerKeyboard(int page, int pages) {
        ObjectNode markup = json.createObjectNode();
        ArrayNode rows = json.createArrayNode();

        ArrayNode row = json.createArrayNode();

        if (page > 1) row.add(inlineBtn("⬅️", "lp:" + (page - 1)));
        else row.add(inlineBtn("·", "lp:noop"));

        row.add(inlineBtn("Стр " + page + "/" + pages, "lp:noop"));

        if (page < pages) row.add(inlineBtn("➡️", "lp:" + (page + 1)));
        else row.add(inlineBtn("·", "lp:noop"));

        rows.add(row);
        markup.set("inline_keyboard", rows);

        return markup;
    }

    public ObjectNode paymentLogsKeyboard(long paymentId, boolean logsShown) {
        ObjectNode markup = json.createObjectNode();
        ArrayNode rows = json.createArrayNode();

        ArrayNode row = json.createArrayNode();

        if (paymentId <= 0) {
            row.add(inlineBtn("Показать лог", "lp:noop"));
        } else {
            if (logsShown) row.add(inlineBtn("Скрыть лог", "pl:" + paymentId + ":hide"));
            else row.add(inlineBtn("Показать лог", "pl:" + paymentId + ":show"));
        }

        rows.add(row);
        markup.set("inline_keyboard", rows);

        return markup;
    }

    ObjectNode inlineBtn(String text, String data) {
        ObjectNode btn = json.createObjectNode();
        btn.put("text", text);
        btn.put("callback_data", data);
        return btn;
    }
}
