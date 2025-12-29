package me.morok.easydonate;

import me.morok.notify.CompositeNotifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PaymentsPoller {

    long intervalMs;
    int lastLimit;

    EasyDonateFacade api;
    PollStateStore state;
    CompositeNotifier notify;

    ScheduledExecutorService exec;

    volatile boolean running;

    public PaymentsPoller(long intervalMs, int lastLimit, EasyDonateFacade api, PollStateStore state, CompositeNotifier notify) {
        this.intervalMs = Math.max(1000, intervalMs);
        this.lastLimit = Math.max(1, lastLimit);
        this.api = api;
        this.state = state;
        this.notify = notify;
    }

    public void start() {
        if (running) return;
        running = true;

        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PaymentsPoller");
            t.setDaemon(true);
            return t;
        });

        exec.scheduleAtFixedRate(this::tickSafe, 500, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        if (exec != null) exec.shutdownNow();
    }

    void tickSafe() {
        try {
            tick();
        } catch (Exception ignored) {
        }
    }

    void tick() {
        List<EasyDonateFacade.Payment> last = api.getLastPayments(lastLimit);
        if (last.isEmpty()) return;

        long lastSeen = state.lastPaymentId;

        List<EasyDonateFacade.Payment> fresh = new ArrayList<>();
        for (EasyDonateFacade.Payment p : last) {
            if (p.id <= 0) continue;
            if (p.id <= lastSeen) continue;
            fresh.add(p);
        }

        if (fresh.isEmpty()) return;

        fresh.sort(Comparator.comparingLong(a -> a.id));

        for (EasyDonateFacade.Payment p : fresh) {
            notify.notifyNewPayment(p);
            state.lastPaymentId = Math.max(state.lastPaymentId, p.id);
        }

        state.save();
    }
}
