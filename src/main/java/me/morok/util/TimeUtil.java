package me.morok.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtil {

    DateTimeFormatter in = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    DateTimeFormatter out = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    public String pretty(String easydonateDate) {
        if (easydonateDate == null || easydonateDate.isBlank()) return null;
        try {
            LocalDateTime dt = LocalDateTime.parse(easydonateDate.trim(), in);
            return dt.format(out);
        } catch (Exception e) {
            return easydonateDate;
        }
    }
}
