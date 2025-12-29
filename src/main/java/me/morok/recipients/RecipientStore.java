package me.morok.recipients;

import java.util.List;

public interface RecipientStore {

    void add(long id);

    List<Long> all();

    void load();

    void save();
}
