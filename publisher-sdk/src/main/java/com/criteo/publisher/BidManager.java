package com.criteo.publisher;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.criteo.publisher.Util.ApplicationStoppedListener;
import com.criteo.publisher.Util.DeviceUtil;
import com.criteo.publisher.Util.NetworkResponseListener;
import com.criteo.publisher.Util.UserAgentCallback;
import com.criteo.publisher.Util.UserAgentHandler;
import com.criteo.publisher.cache.SdkCache;
import com.criteo.publisher.model.AdUnit;
import com.criteo.publisher.model.Config;
import com.criteo.publisher.model.Publisher;
import com.criteo.publisher.model.Slot;
import com.criteo.publisher.model.User;
import com.criteo.publisher.network.CdbDownloadTask;
import com.google.android.gms.ads.doubleclick.PublisherAdRequest;

import java.util.ArrayList;
import java.util.List;

public class BidManager implements NetworkResponseListener, ApplicationStoppedListener {
    private static final String CRT_CPM = "crt_cpm";
    private static final String CRT_DISPLAY_URL = "crt_displayUrl";
    private static final int SECOND_TO_MILLI = 1000;
    private static final int PROFILE_ID = 235;
    private final List<AdUnit> adUnits;
    private final Context mContext;
    private final SdkCache cache;
    private final Publisher publisher;
    private final User user;
    private CdbDownloadTask cdbDownloadTask;
    private long cdbTimeToNextCall = 0;
    private Config config;
    private String userAgent;

    BidManager(Context context, int networkId, List<AdUnit> adUnits) {
        this.mContext = context;
        this.adUnits = adUnits;
        this.cache = new SdkCache();
        publisher = new Publisher(mContext);
        publisher.setNetworkId(networkId);
        user = new User();
        userAgent = "";
    }

    /**
     * Method to start new CdbDownload Asynctask
     *
     * @param callConfig
     * @param userAgent
     */
    private void startCdbDownloadTask(boolean callConfig, String userAgent, AdUnit adUnit) {
        List<AdUnit> prefetchAdUnits = new ArrayList<AdUnit>();
        prefetchAdUnits.add(adUnit);
        startCdbDownloadTask(callConfig, userAgent, prefetchAdUnits);
    }

    private void startCdbDownloadTask(boolean callConfig, String userAgent, List<AdUnit> prefetchAdUnits) {
        if (cdbDownloadTask != null && cdbDownloadTask.getStatus() != AsyncTask.Status.RUNNING &&
                cdbTimeToNextCall < System.currentTimeMillis()) {
            cdbDownloadTask = new CdbDownloadTask(mContext, this, callConfig, userAgent);
            cdbDownloadTask.execute(PROFILE_ID, user, publisher, prefetchAdUnits);
        }
    }


    PublisherAdRequest.Builder enrichBid(PublisherAdRequest.Builder request, AdUnit adUnit) {
        if (config != null && config.isKillSwitch()) {
            return request;
        }
        Slot slot = validateAndPrefetchSlotInCache(adUnit);
        if (slot != null) {
            request.addCustomTargeting(CRT_CPM, slot.getCpm());
            request.addCustomTargeting(CRT_DISPLAY_URL, DeviceUtil.createDfpCompatibleDisplayUrl(slot.getDisplayUrl()));
        }
        return request;

    }

    private Slot validateAndPrefetchSlotInCache(AdUnit adUnit) {
        Slot peekSlot = cache.peekAdUnit(adUnit.getPlacementId(), adUnit.getSize().getFormattedSize());
        if (peekSlot == null) {
            return null;
        }
        float cpm = Float.valueOf(peekSlot.getCpm());
        long ttl = peekSlot.getTtl();
        long expiryTimeMillis = ttl * SECOND_TO_MILLI + peekSlot.getTimeOfDownload();
        //If cpm and ttl in slot are 0:
        // Prefetch from CDB and do not update request;
        if (cpm == 0 && ttl == 0) {
            cache.remove(adUnit.getPlacementId(),
                    adUnit.getSize().getFormattedSize());
            startCdbDownloadTask(false, userAgent, adUnit);
            return null;
        }
        //If cpm is 0, ttl in slot > 0
        // we will stay silent until ttl expires;
        else if (cpm == 0 && ttl > 0
                && expiryTimeMillis > System.currentTimeMillis()) {
            return null;
        } else {
            //If cpm > 0, ttl > 0 but we are done staying silent
            Slot slot = cache.getAdUnit(adUnit.getPlacementId(),
                    adUnit.getSize().getFormattedSize());
            startCdbDownloadTask(false, userAgent, adUnit);
            return slot;
        }

    }


    @Override
    public void setAdUnits(List<Slot> slots) {
        cache.addAll(slots);
    }

    @Override
    public void setConfig(Config config) {
        this.config = config;
    }

    @Override
    public void setTimeToNextCall(int seconds) {
        this.cdbTimeToNextCall = System.currentTimeMillis() + seconds * 1000;
    }

    @Override
    public void onApplicationStopped() {
        if (cdbDownloadTask != null && cdbDownloadTask.getStatus() == AsyncTask.Status.RUNNING) {
            cdbDownloadTask.cancel(true);
        }
    }


    /**
     * Method to post new Handler to the Main Thread
     * When we get "useragent" from the Listener we start new CdbDownload Asynctask
     * to get Cdb and Config
     */
    protected void prefetch() {

        final Handler mainHandler = new UserAgentHandler(Looper.getMainLooper(), new UserAgentCallback() {
            @Override
            public void done(String useragent) {
                userAgent = useragent;
                startCdbDownloadTask(true, userAgent, adUnits);

            }
        });

        final Runnable setUserAgentTask = new Runnable() {
            @Override
            public void run() {

                String userAgent = DeviceUtil.getUserAgent(mContext);
                Message msg = mainHandler.obtainMessage();
                Bundle bundle = new Bundle();
                bundle.putString("userAgent", userAgent);
                msg.setData(bundle);
                mainHandler.sendMessage(msg);

            }

        };
        mainHandler.post(setUserAgentTask);

    }


}
