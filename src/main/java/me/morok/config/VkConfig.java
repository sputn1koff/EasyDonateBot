package me.morok.config;

import java.util.ArrayList;
import java.util.List;

public class VkConfig {

    public boolean enabled;

    public long groupId;
    public String token;

    public List<Long> adminUserIds = new ArrayList<>();

    public LongPoll longpoll = new LongPoll();

    public long pollingIntervalMs;
    public int lastPaymentsLimit;

    public Storage storage = new Storage();
    public Ui ui = new Ui();
    public Messages messages = new Messages();

    public static class LongPoll {
        public int waitSec;
    }

    public static class Storage {
        public String recipientsFile;
        public String stateFile;
    }

    public static class Ui {
        public String buttonLastPayments;
        public String titleNewPayment;
        public String titleLastPayments;
    }

    public static class Messages {
        public String start;
        public String noPayments;
    }
}
