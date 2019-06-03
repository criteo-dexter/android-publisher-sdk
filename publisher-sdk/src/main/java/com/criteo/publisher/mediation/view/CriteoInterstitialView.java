package com.criteo.publisher.mediation.view;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import com.criteo.publisher.Util.CriteoResultReceiver;
import com.criteo.publisher.Util.DeviceUtil;
import com.criteo.publisher.Util.InterstitialAdUnit;
import com.criteo.publisher.mediation.controller.CriteoInterstitialEventController;
import com.criteo.publisher.mediation.controller.WebViewDownloader;
import com.criteo.publisher.mediation.listeners.CriteoInterstitialAdListener;
import com.criteo.publisher.model.CacheAdUnit;
import com.criteo.publisher.model.WebViewData;

public class CriteoInterstitialView {

    private InterstitialAdUnit interstitialAdUnit;

    private Context context;

    private CriteoInterstitialAdListener criteoInterstitialAdListener;

    private CriteoInterstitialEventController criteoInterstitialEventController;


    public CriteoInterstitialView(Context context, InterstitialAdUnit interstitialAdUnit) {
        this.context = context;
        this.interstitialAdUnit = interstitialAdUnit;
    }

    public void setCriteoInterstitialAdListener(CriteoInterstitialAdListener criteoInterstitialAdListener) {
        this.criteoInterstitialAdListener = criteoInterstitialAdListener;

    }

    public void loadAd() {
        if (criteoInterstitialEventController == null) {
            criteoInterstitialEventController = new CriteoInterstitialEventController(
                    criteoInterstitialAdListener, new WebViewDownloader(new WebViewData()));
        }

        criteoInterstitialEventController.fetchAdAsync(interstitialAdUnit);
    }

    public boolean isAdLoaded() {
        return criteoInterstitialEventController.isAdLoaded();
    }

    public void show() {
        if (isAdLoaded()) {
            Intent intent = new Intent(context, CriteoInterstitialActivity.class);
            Bundle bundle = new Bundle();
            bundle.putString("webviewdata", criteoInterstitialEventController.getWebViewDataContent());
            CriteoResultReceiver criteoResultReceiver = new CriteoResultReceiver(new Handler(),
                    criteoInterstitialAdListener);
            bundle.putParcelable("resultreceiver", criteoResultReceiver);
            intent.putExtras(bundle);
            context.startActivity(intent);
        }
    }


}