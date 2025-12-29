package me.morok.easydonate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

public class PollStateStore {

    ObjectMapper json = new ObjectMapper();

    Path file;

    public long lastPaymentId;

    public PollStateStore(Path file) {
        this.file = file;
    }

    public void load() {
        try {
            if (Files.exists(file) == false) return;

            String s = Files.readString(file);
            lastPaymentId = json.readTree(s).path("lastPaymentId").asLong(0);
        } catch (Exception ignored) {
        }
    }

    public void save() {
        try {
            ObjectNode n = json.createObjectNode();
            n.put("lastPaymentId", lastPaymentId);

            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(n));
        } catch (Exception ignored) {
        }
    }
}
