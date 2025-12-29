package me.morok.recipients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public class PagerStateStore {

    ObjectMapper json = new ObjectMapper();

    Path file;

    ConcurrentHashMap<Long, Integer> pages = new ConcurrentHashMap<>();

    public PagerStateStore(Path file) {
        this.file = file;
    }

    public int get(long id) {
        Integer v = pages.get(id);
        if (v == null || v < 1) return 1;
        return v;
    }

    public void set(long id, int page) {
        if (id <= 0) return;
        if (page < 1) page = 1;
        pages.put(id, page);
    }

    public void clear(long id) {
        if (id <= 0) return;
        pages.remove(id);
    }

    public void load() {
        try {
            if (file == null) return;
            if (!Files.exists(file)) return;

            String s = Files.readString(file);
            JsonNode root = json.readTree(s);
            if (root == null || !root.isObject()) return;

            pages.clear();

            root.fields().forEachRemaining(e -> {
                try {
                    long id = Long.parseLong(e.getKey());
                    int page = e.getValue().asInt(1);
                    if (id > 0 && page > 0) pages.put(id, page);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    public void save() {
        try {
            if (file == null) return;

            ObjectNode root = json.createObjectNode();
            for (var e : pages.entrySet()) {
                long id = e.getKey();
                int page = e.getValue() == null ? 1 : e.getValue();
                if (id <= 0) continue;
                if (page <= 0) page = 1;
                root.put(String.valueOf(id), page);
            }

            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception ignored) {
        }
    }
}
