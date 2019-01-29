package com.criteo.pubsdk.model;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import com.criteo.pubsdk.BuildConfig;
import com.criteo.pubsdk.Util.DeviceUtil;
import com.google.gson.JsonObject;

public class User implements Parcelable {
    private static final String DEVICE_ID = "deviceId";
    private static final String DEVICE_ID_TYPE = "deviceIdType";
    private static final String DEVICE_MODEL = "deviceModel";
    private static final String DEVICE_OS = "deviceOs";
    private static final String SDK_VER = "sdkver";
    private static final String LMT = "lmt";
    private static final String CONNECTION = "connection";
    private static final String USER_AGENT = "userAgent";
    private static final String GAID = "gaid";
    private static final String ANDROID = "android";
    private static final int LMT_VAL = 0;

    private String deviceId;
    private String deviceIdType;
    private String deviceModel;
    private String deviceOs;
    private String sdkVer;
    private int lmt;
    private String connection;
    private String userAgent;

    public User(Context context) {
        deviceId = DeviceUtil.getDeviceId(context);
        deviceIdType = GAID;
        deviceModel = DeviceUtil.getDeviceModel();
        deviceOs = ANDROID;
        sdkVer = BuildConfig.VERSION_NAME;
        lmt = LMT_VAL;
        userAgent = DeviceUtil.getUserAgent(context);
    }


    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceIdType() {
        return deviceIdType;
    }

    public void setDeviceIdType(String deviceIdType) {
        this.deviceIdType = deviceIdType;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getDeviceOs() {
        return deviceOs;
    }

    public void setDeviceOs(String deviceOs) {
        this.deviceOs = deviceOs;
    }

    public String getSdkVer() {
        return sdkVer;
    }

    public void setSdkVer(String sdkVer) {
        this.sdkVer = sdkVer;
    }

    public int getLmt() {
        return lmt;
    }

    public void setLmt(int lmt) {
        this.lmt = lmt;
    }

    public String getConnection() {
        return connection;
    }

    public void setConnection(String connection) {
        this.connection = connection;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty(DEVICE_ID, deviceId);
        object.addProperty(DEVICE_ID_TYPE, deviceIdType);
        object.addProperty(DEVICE_MODEL, deviceModel);
        object.addProperty(DEVICE_OS, deviceOs);
        object.addProperty(SDK_VER, sdkVer);
        object.addProperty(LMT, lmt);
        object.addProperty(CONNECTION, connection);
        object.addProperty(USER_AGENT, userAgent);
        return object;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.deviceId);
        dest.writeString(this.deviceIdType);
        dest.writeString(this.deviceModel);
        dest.writeString(this.deviceOs);
        dest.writeString(this.sdkVer);
        dest.writeInt(this.lmt);
        dest.writeString(this.connection);
        dest.writeString(this.userAgent);
    }

    protected User(Parcel in) {
        this.deviceId = in.readString();
        this.deviceIdType = in.readString();
        this.deviceModel = in.readString();
        this.deviceOs = in.readString();
        this.sdkVer = in.readString();
        this.lmt = in.readInt();
        this.connection = in.readString();
        this.userAgent = in.readString();
    }

    public static final Parcelable.Creator<User> CREATOR = new Parcelable.Creator<User>() {
        @Override
        public User createFromParcel(Parcel source) {
            return new User(source);
        }

        @Override
        public User[] newArray(int size) {
            return new User[size];
        }
    };
}