package me.morok.recipients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;

public class VkRecipientsStore implements RecipientStore {

    ObjectMapper json = new ObjectMapper();

    Path file;

    ConcurrentSkipListSet<Long> ids = new ConcurrentSkipListSet<>();

    public VkRecipientsStore(Path file) {
        this.file = file;
    }

    @Override
    public void add(long id) {
        if (id == 0) return;
        ids.add(id);
    }

    @Override
    public List<Long> all() {
        return new ArrayList<>(ids);
    }

    @Override
    public void load() {
        try {
            if (Files.exists(file) == false) return;

            String s = Files.readString(file);
            ArrayNode arr = (ArrayNode) json.readTree(s);
            ids.clear();

            for (int i = 0; i < arr.size(); i++) {
                long v = arr.get(i).asLong(0);
                if (v != 0) ids.add(v);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void save() {
        try {
            ArrayNode arr = json.createArrayNode();
            for (Long id : ids) arr.add(id);

            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(arr));
        } catch (Exception ignored) {
        }
    }
}
