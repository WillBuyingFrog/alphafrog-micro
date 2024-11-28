package world.willfrog.alphafrogmicro.common.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class DateConvertUtils {

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
}