package me.morok.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.morok.config.VkConfig;

public class VkKeyboardFactory {

    ObjectMapper json = new ObjectMapper();

    VkConfig cfg;

    public VkKeyboardFactory(VkConfig cfg) {
        this.cfg = cfg;
    }

    public ObjectNode lastPaymentsKeyboard() {
        ObjectNode kb = json.createObjectNode();
        kb.put("one_time", false);
        kb.put("inline", false);

        ArrayNode buttons = json.createArrayNode();
        buttons.add(row(primary(cfg.ui.buttonLastPayments)));

        kb.set("buttons", buttons);
        return kb;
    }

    public ObjectNode pagerKeyboard(int page, int pages) {
        ObjectNode kb = json.createObjectNode();
        kb.put("one_time", false);
        kb.put("inline", false);

        ArrayNode buttons = json.createArrayNode();

        ArrayNode nav = json.createArrayNode();

        if (page > 1) nav.add(primary("⬅️"));
        else nav.add(secondary("·"));

        nav.add(secondary("Стр " + page + "/" + pages));

        if (page < pages) nav.add(primary("➡️"));
        else nav.add(secondary("·"));

        buttons.add(nav);

        buttons.add(row(primary(cfg.ui.buttonLastPayments)));

        kb.set("buttons", buttons);
        return kb;
    }

    ArrayNode row(ObjectNode btn) {
        ArrayNode row = json.createArrayNode();
        row.add(btn);
        return row;
    }

    ObjectNode primary(String label) {
        return button(label, "primary");
    }

    ObjectNode secondary(String label) {
        return button(label, "secondary");
    }

    ObjectNode button(String label, String color) {
        ObjectNode btn = json.createObjectNode();
        ObjectNode action = json.createObjectNode();

        action.put("type", "text");
        action.put("label", label);

        btn.set("action", action);
        btn.put("color", color);

        return btn;
    }
}
