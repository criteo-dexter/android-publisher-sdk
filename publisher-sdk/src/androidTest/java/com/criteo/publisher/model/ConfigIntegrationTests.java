package com.criteo.publisher.model;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import com.criteo.publisher.Criteo;
import com.criteo.publisher.CriteoInitException;
import com.criteo.publisher.CriteoUtil;
import com.criteo.publisher.R;

import com.criteo.publisher.Util.MockedDependenciesRule;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ConfigIntegrationTests {
    @Rule
    public MockedDependenciesRule mockedDependenciesRule  = new MockedDependenciesRule();

    private Context context;
    private String CACHED_KILL_SWITCH = "CriteoCachedKillSwitch";

    @Before
    public void setup() {
        context = InstrumentationRegistry.getTargetContext();
        clearSharedPrefs();
    }

    @After
    public void tearDown() {
       clearSharedPrefs();
    }

    @Test
    public void testConfigWithCriteoInit() throws CriteoInitException {
        CriteoUtil.givenInitializedCriteo();

        Application app = (Application) InstrumentationRegistry.getTargetContext().getApplicationContext();
        Criteo.init(app, "B-056946", null);
        verify(mockedDependenciesRule.getDependencyProvider()).provideConfig(app);
    }

    @Test
    public void testConfigWithoutCriteoInit()  {
        verify(mockedDependenciesRule.getDependencyProvider(), never()).provideConfig(any(Context.class));
    }

    @Test
    public void testConfigConstructor() {
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.shared_preferences), Context.MODE_PRIVATE);
        // Assert that default value isn't cached
        Assert.assertFalse(sharedPref.contains(CACHED_KILL_SWITCH));
    }

    @Test
    public void testConfigConstructorCachedKillSwitch() {
        //set the killSwitch to true in sharedPrefs
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.shared_preferences), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(CACHED_KILL_SWITCH, true);
        editor.commit();

        // test
        Config config = new Config(context);
        // Assert that constructor hasn't cleared the cache
        Assert.assertTrue(sharedPref.contains(CACHED_KILL_SWITCH));
        boolean killSwitchFromCache = sharedPref.getBoolean(CACHED_KILL_SWITCH, false);
        Assert.assertTrue(killSwitchFromCache);
        Assert.assertTrue(config.isKillSwitchEnabled());
    }

    @Test
    public void testRefreshConfig() {
        Config config = new Config(context);
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.shared_preferences), Context.MODE_PRIVATE);
        // The config ctor shouldn't set the default to the shared prefs
        Assert.assertFalse(sharedPref.contains(CACHED_KILL_SWITCH));

        JSONObject json = new JSONObject();
        try {
            json.put("killSwitch", true);
        } catch (JSONException je) {
            Assert.fail("JSON exception" + je.getMessage());
        }

        // test
        config.refreshConfig(json);

        Assert.assertTrue(config.isKillSwitchEnabled());
        Assert.assertTrue(sharedPref.getBoolean(CACHED_KILL_SWITCH, false));
    }

    @Test
    public void testRefreshConfigCachedKillSwitch() {
        //set the killSwitch to true in sharedPrefs
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.shared_preferences), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(CACHED_KILL_SWITCH, true);
        editor.commit();

        Config config = new Config(context);

        JSONObject json = new JSONObject();
        try {
            json.put("killSwitch", false);
        } catch (JSONException je) {
            Assert.fail("JSON exception" + je.getMessage());
        }

        // test
        config.refreshConfig(json);

        Assert.assertFalse(config.isKillSwitchEnabled());
        // This should flip from the explicitly set true to false from the JSON.
        // To prevent confusion of where the 'false' value came from
        // changing the defaultValue of getBoolean to true
        Assert.assertFalse(sharedPref.getBoolean(CACHED_KILL_SWITCH, true));
    }

    @Test
    public void testRefreshBadJson() {
        //set the killSwitch to true in sharedPrefs
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.shared_preferences), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(CACHED_KILL_SWITCH, true);
        editor.commit();

        Config config = new Config(context);

        // json intentionally left blank
        JSONObject json = new JSONObject();

        // test
        config.refreshConfig(json);

        Assert.assertTrue(config.isKillSwitchEnabled());
        // this should not flip from the explicitly set value
        // as the json doesn't have a kill switch value to overwrite
        Assert.assertTrue(sharedPref.getBoolean(CACHED_KILL_SWITCH, false));
    }

    private void clearSharedPrefs() {
        //clear the sharedPrefs
        SharedPreferences sharedPref = context.getSharedPreferences(
            context.getString(R.string.shared_preferences), Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = sharedPref.edit();
        editor.remove(CACHED_KILL_SWITCH);
        editor.commit();
    }
}