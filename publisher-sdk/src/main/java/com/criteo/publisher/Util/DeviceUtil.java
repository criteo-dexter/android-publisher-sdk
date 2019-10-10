package com.criteo.publisher.Util;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import com.criteo.publisher.model.AdSize;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class DeviceUtil {

    private static final String CRITEO_LOGGING = "CRITEO_LOGGING";
    private static final String DEVICE_ID_LIMITED = "00000000-0000-0000-0000-000000000000";

    private static AdSize sizePortrait = new AdSize(0, 0);
    private static AdSize sizeLandscape = new AdSize(0, 0);

    private DeviceUtil() {
    }

    public static void setScreenSize(int screenWidth, int screenHeight) {
        sizePortrait = new AdSize(screenWidth, screenHeight);
        sizeLandscape = new AdSize(screenHeight, screenWidth);
    }

    public static AdSize getSizePortrait() {
        return sizePortrait;
    }

    public static AdSize getSizeLandscape() {
        return sizeLandscape;
    }

    public static String getDeviceModel() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model;
        } else {
            return manufacturer + " " + model;
        }
    }

    public static String getAdvertisingId(Context context) {
        try {
            if (AdvertisingInfo.getInstance().isLimitAdTrackingEnabled(context)) {
                return DEVICE_ID_LIMITED;
            }
            return AdvertisingInfo.getInstance().getAdvertisingId(context);
        } catch (Exception e) {
            Log.e("DeviceUtil", "Error trying to get Advertising id: " + e.getMessage());
        }
        return null;
    }

    public static int isLimitAdTrackingEnabled(Context context) {
        try {
            return AdvertisingInfo.getInstance().isLimitAdTrackingEnabled(context) ? 1 : 0;
        } catch (Exception e) {
            Log.e("DeviceUtil", "Error trying to check limited ad tracking: " + e.getMessage());
        }
        return 0;
    }

    public static String createDfpCompatibleString(String stringToEncode) {
        if (TextUtils.isEmpty(stringToEncode)) {
            return null;
        }
        try {
            byte[] byteUrl = stringToEncode.getBytes(StandardCharsets.UTF_8);
            String base64Url = Base64.encodeToString(byteUrl, Base64.NO_WRAP);
            String utf8 = StandardCharsets.UTF_8.name();
            return URLEncoder.encode(URLEncoder.encode(base64Url, utf8), utf8).toString();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static boolean isLoggingEnabled() {
        String log = System.getenv(CRITEO_LOGGING);
        return TextUtils.isEmpty(log) ? false : Boolean.parseBoolean(log);
    }

}
