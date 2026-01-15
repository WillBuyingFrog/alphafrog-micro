package world.willfrog.alphafrogmicro.common.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DateConvertUtils {

    private static final DateTimeFormatter YYYYMMDD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static Long convertDateStrToLong(String dateStr, String format) {
        if (format.equals("yyyyMMdd")) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
            dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            try {
                Date parsedDate = dateFormat.parse(dateStr);
                return parsedDate.getTime();
            } catch (ParseException e) {
                e.printStackTrace();
                return (long) -1;
            }
        } else {
            return 0L;
        }
    }

    public static String convertTimestampToString(Long timestamp, String format) {
        if (format.equals("yyyyMMdd")) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
            return dateFormat.format(new Date(timestamp));
        } else {
            return "";
        }
    }

    /**
     * Converts a LocalDate object to its corresponding millisecond timestamp
     * representing 00:00:00 in Asia/Shanghai timezone.
     *
     * @param date The LocalDate to convert.
     * @return A long representing the millisecond timestamp.
     * @throws IllegalArgumentException if the input date is null.
     */
    public static long convertLocalDateToMsTimestamp(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Input LocalDate cannot be null for timestamp conversion.");
        }
        String dateStr = date.format(YYYYMMDD_FORMATTER);
        // Utilize existing method that converts "yyyyMMdd" string to Asia/Shanghai 00:00:00 timestamp
        Long timestamp = convertDateStrToLong(dateStr, "yyyyMMdd");
        if (timestamp == null || timestamp == -1L) { // Check based on your existing convertDateStrToLong logic
            throw new RuntimeException("Failed to convert LocalDate to timestamp via string conversion for date: " + dateStr);
        }
        return timestamp;
    }

    /**
     * Converts a millisecond timestamp (representing 00:00:00 in Asia/Shanghai timezone)
     * to a LocalDate object.
     *
     * @param msTimestamp The millisecond timestamp.
     * @return A LocalDate object.
     * @throws IllegalArgumentException if the timestamp results in an invalid date string.
     */
    public static LocalDate convertTimestampToLocalDate(long msTimestamp) {
        // Utilize existing method that converts timestamp to "yyyyMMdd" string
        String dateStr = convertTimestampToString(msTimestamp, "yyyyMMdd");
        if (dateStr == null || dateStr.isEmpty()) {
             throw new IllegalArgumentException("Failed to convert timestamp to date string for timestamp: " + msTimestamp);
        }
        try {
            return LocalDate.parse(dateStr, YYYYMMDD_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Failed to parse date string '" + dateStr + "' (from timestamp " + msTimestamp + ") into LocalDate: " + e.getMessage(), e);
        }
    }
}