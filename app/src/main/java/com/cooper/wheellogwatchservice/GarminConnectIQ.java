package com.cooper.wheellogwatchservice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener;
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener;
import com.garmin.android.connectiq.ConnectIQ.IQApplicationInfoListener;
import com.garmin.android.connectiq.ConnectIQ.IQConnectType;
import com.garmin.android.connectiq.ConnectIQ.IQDeviceEventListener;
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus;
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;
import com.garmin.monkeybrains.serialization.MonkeyHash;

import org.json.JSONException;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

public class GarminConnectIQ extends Service implements IQApplicationInfoListener, IQDeviceEventListener, IQApplicationEventListener, ConnectIQListener {
    public static final String TAG = GarminConnectIQ.class.getSimpleName();
    public static final String APP_ID = "df8bf0ab-1828-4037-a328-ee86d29d0501";
    // This will require Garmin Connect V4.22
    // https://forums.garmin.com/developer/connect-iq/i/bug-reports/connect-version-4-20-broke-local-http-access
    public static final boolean FEATURE_FLAG_NANOHTTPD = true;

    static List<String> errors = new ArrayList<>();

    public enum MessageType {
        EUC_DATA,
        PLAY_HORN,
        HTTP_READY,
    }

    public static final int MESSAGE_KEY_MSG_TYPE     = -2;
    public static final int MESSAGE_KEY_MSG_DATA     = -1;
    public static final int MESSAGE_KEY_SPEED        = 0;
    public static final int MESSAGE_KEY_BATTERY      = 1;
    public static final int MESSAGE_KEY_TEMPERATURE  = 2;
    public static final int MESSAGE_KEY_FAN_STATE    = 3;
    public static final int MESSAGE_KEY_BT_STATE     = 4;
    public static final int MESSAGE_KEY_VIBE_ALERT   = 5;
    public static final int MESSAGE_KEY_USE_MPH      = 6;
    public static final int MESSAGE_KEY_MAX_SPEED    = 7;
    public static final int MESSAGE_KEY_RIDE_TIME    = 8;
    public static final int MESSAGE_KEY_DISTANCE     = 9;
    public static final int MESSAGE_KEY_TOP_SPEED    = 10;
    public static final int MESSAGE_KEY_READY        = 11;
    public static final int MESSAGE_KEY_POWER        = 12;

    public static final int MESSAGE_KEY_HTTP_PORT    = 99;

    int lastSpeed = 0;
    int lastBattery = 0;
    int lastTemperature = 0;
    int lastFanStatus = 0;
    int lastRideTime = 0;
    int lastDistance = 0;
    int lastTopSpeed = 0;
    boolean lastConnectionState = false;
    boolean lastUseMph = false;
    int lastMaxSpeed = 0;
    int lastPower = 0;

    private boolean mSdkReady;
    private ConnectIQ mConnectIQ;
    private List<IQDevice> mDevices;
    private IQDevice mDevice;
    private IQApp mMyApp;
    private boolean isConnected = false;

    private GarminConnectIQWebServer mWebServer;

    private final IWatchInterface.Stub binder = new IWatchInterface.Stub() {
        @Override
        public boolean isConnected() {
            return mDevice != null && mSdkReady && isConnected;
        }

        @Override
        public List<String> getErrors() {
            List<String> temp = new ArrayList<>(errors);
            errors.clear();
            return temp;
        }

        @Override
        public void sendData(String[] data) {
            refreshData(data);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        // Setup Connect IQ
        mMyApp = new IQApp(APP_ID);
        mConnectIQ = ConnectIQ.getInstance(this, IQConnectType.WIRELESS);
        mConnectIQ.initialize(this, true, this);

        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG,"onDestroy");
        super.onDestroy();

        try {
            mConnectIQ.unregisterAllForEvents();
            mConnectIQ.shutdown(this);
        } catch (InvalidStateException e) {
            // This is usually because the SDK was already shut down
            // so no worries.
        }

        stopWebServer();
        stopForeground(true);
    }

    // General METHODS
    private void populateDeviceList() {
        Log.d(TAG,"populateDeviceList");

        try {
            mDevices = mConnectIQ.getKnownDevices();

            if (mDevices != null && !mDevices.isEmpty()) {
                mDevice = mDevices.get(0);
                registerWithDevice();
            }

        } catch (InvalidStateException e) {
            // This generally means you forgot to call initialize(), but since
            // we are in the callback for initialize(), this should never happen
        } catch (ServiceUnavailableException e) {
            // This will happen if for some reason your app was not able to connect
            // to the ConnectIQ service running within Garmin Connect Mobile.  This
            // could be because Garmin Connect Mobile is not installed or needs to
            // be upgraded.
            errors.add("The ConnectIQ service is unavailable.");
        }
    }

    private void registerWithDevice() {
        Log.d(TAG,"registerWithDevice");

        if (mDevice != null && mSdkReady) {
            // Register for device status updates
            try {
                mConnectIQ.registerForDeviceEvents(mDevice, this);
            } catch (InvalidStateException e) {
                Log.wtf(TAG, "InvalidStateException:  We should not be here!");
            }

            // Register for application status updates
            try {
                mConnectIQ.getApplicationInfo(APP_ID, mDevice, this);
            } catch (InvalidStateException e1) {
                Log.d(TAG, "e1: " + e1.getMessage());
            } catch (ServiceUnavailableException e1) {
                Log.d(TAG, "e2: " + e1.getMessage());
            }

            // Register to receive messages from the device
            try {
                mConnectIQ.registerForAppEvents(mDevice, mMyApp, this);
            } catch (InvalidStateException e) {
                Toast.makeText(this, "ConnectIQ is not in a valid state", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void unregisterWithDevice() {
        Log.d(TAG,"unregisterWithDevice");

        if (mDevice != null && mSdkReady) {
            // It is a good idea to unregister everything and shut things down to
            // release resources and prevent unwanted callbacks.
            try {
                mConnectIQ.unregisterForDeviceEvents(mDevice);

                if (mMyApp != null) {
                    mConnectIQ.unregisterForApplicationEvents(mDevice, mMyApp);
                }
            } catch (InvalidStateException ignored) {
            }
        }
    }

    private void refreshData(String[] raw) {
        try {
            HashMap<Object, Object> data = new HashMap<>();

            lastSpeed = Integer.parseInt(raw[0]) / 10;
            data.put(MESSAGE_KEY_SPEED, lastSpeed);

            lastBattery = Integer.parseInt(raw[1]);
            data.put(MESSAGE_KEY_BATTERY, lastBattery);

            lastTemperature = Integer.parseInt(raw[2]);
            data.put(MESSAGE_KEY_TEMPERATURE, lastTemperature);

            lastFanStatus = Integer.parseInt(raw[3]);
            data.put(MESSAGE_KEY_FAN_STATE, lastFanStatus);

            lastConnectionState = raw[4].equals("1");
            data.put(MESSAGE_KEY_BT_STATE, lastConnectionState);

            data.put(MESSAGE_KEY_VIBE_ALERT, false);

            lastUseMph = raw[5].equals("1");
            data.put(MESSAGE_KEY_USE_MPH, lastUseMph);

            lastMaxSpeed = Integer.parseInt(raw[6]);
            data.put(MESSAGE_KEY_MAX_SPEED, lastMaxSpeed);

            lastRideTime = Integer.parseInt(raw[7]);
            data.put(MESSAGE_KEY_RIDE_TIME, lastRideTime);

            lastDistance = Integer.parseInt(raw[8]);
            data.put(MESSAGE_KEY_DISTANCE, lastDistance/100);

            lastTopSpeed = Integer.parseInt(raw[9]);
            data.put(MESSAGE_KEY_TOP_SPEED, lastTopSpeed/10);

            lastPower = Integer.parseInt(raw[10]);
            data.put(MESSAGE_KEY_POWER, lastPower);

            HashMap<Object, Object> message = new HashMap<>();
            message.put(MESSAGE_KEY_MSG_TYPE, MessageType.EUC_DATA.ordinal());
            message.put(MESSAGE_KEY_MSG_DATA, new MonkeyHash(data));

            try {
                mConnectIQ.sendMessage(mDevice, mMyApp, message, (device, app, status) -> {
                    Log.d(TAG, "message status: " + status.name());

                    if (!status.name().equals("SUCCESS"))
                        Toast.makeText(GarminConnectIQ.this, status.name(), Toast.LENGTH_LONG).show();
                });
            } catch (InvalidStateException e) {
                Log.e(TAG, "ConnectIQ is not in a valid state");
                errors.add("ConnectIQ is not in a valid state");
            } catch (ServiceUnavailableException e) {
                Log.e(TAG, "ConnectIQ service is unavailable.   Is Garmin Connect Mobile installed and running?");
                errors.add("ConnectIQ service is unavailable.   Is Garmin Connect Mobile installed and running?");
            }
        } catch (Exception ex) {
            Log.e(TAG, "refreshData", ex);
            errors.add("Error refreshing data");
        }
    }

    // IQApplicationInfoListener METHODS
    @Override
    public void onApplicationInfoReceived(IQApp app) {
        Log.d(TAG,"onApplicationInfoReceived");
        Log.d(TAG, app.toString());
    }

    @Override
    public void onApplicationNotInstalled(String arg0) {
        Log.d(TAG,"onApplicationNotInstalled");

        // The WheelLog app is not installed on the device so we have
        // to let the user know to install it.

        errors.add("The WheelLog App used with this application is not installed on your ConnectIQ device. Please install the widget and try again.");
        try {
            mConnectIQ.openStore(APP_ID);
        } catch (InvalidStateException ignored) {
        } catch (ServiceUnavailableException ignored) {
        }
    }

    // IQDeviceEventListener METHODS
    @Override
    public void onDeviceStatusChanged(IQDevice device, IQDeviceStatus status) {
        Log.d(TAG,"onDeviceStatusChanged");
        Log.d(TAG, "status is:" + status.name());
        isConnected = false;

        switch(status.name()) {
            case "CONNECTED":
                // Disabled the push method for now until a dev from garmin can shed some light on the
                // intermittent FAILURE_DURING_TRANSFER that we have seen. This is documented here:
                // https://forums.garmin.com/developer/connect-iq/f/legacy-bug-reports/5144/failure_during_transfer-issue-again-now-using-comm-sample
                if (!FEATURE_FLAG_NANOHTTPD) {
                    isConnected = true;
                }

                // As a workaround, start a nanohttpd server that will listen for data requests from the watch. This is
                // also documented on the link above and is apparently a good workaround for the meantime. In our implementation
                // we instanciate the httpd server on an ephemeral port and send a message to the watch to tell it on which port
                // it can request its data.
                if (FEATURE_FLAG_NANOHTTPD) {
                    startWebServer();
                }
                break;
            case "NOT_PAIRED":
            case "NOT_CONNECTED":
            case "UNKNOWN":
                stopWebServer();
        }
    }

    // IQApplicationEventListener
    @Override
    public void onMessageReceived(IQDevice device, IQApp app, List<Object> message, IQMessageStatus status) {
        Log.d(TAG,"onMessageReceived");

        // We know from our widget that it will only ever send us strings, but in case
        // we get something else, we are simply going to do a toString() on each object in the
        // message list.
        StringBuilder builder = new StringBuilder();

        if (message.size() > 0) {
            for (Object o : message) {
                if (o == null) {
                    builder.append("<null> received");
                } else if (o instanceof HashMap) {
                    try {
                        HashMap msg = (HashMap)o;
                        int msgType = (int)msg.get(MESSAGE_KEY_MSG_TYPE);
                        if (msgType == MessageType.PLAY_HORN.ordinal()) {
                            playHorn();
                        }
                        builder = null;
                    } catch (Exception ex) {
                        builder.append("MonkeyHash received:\n\n");
                        builder.append(o.toString());
                    }
                } else {
                    builder.append(o.toString());
                    builder.append("\r\n");
                }
            }
        } else {
            builder.append("Received an empty message from the ConnectIQ application");
        }

        if (builder != null) {
            Toast.makeText(getApplicationContext(), builder.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    // ConnectIQListener METHODS
    @Override
    public void onInitializeError(IQSdkErrorStatus errStatus) {
        Log.d(TAG,"sdk initialization error");
        mSdkReady = false;
    }

    @Override
    public void onSdkReady() {
        Log.d(TAG,"sdk is ready");
        mSdkReady = true;
        populateDeviceList();
    }

    @Override
    public void onSdkShutDown() {
        Log.d(TAG,"sdk shut down");
        mSdkReady = false;
    }

    public void playHorn() {
        Context context = getApplicationContext();
        MediaPlayer mp = MediaPlayer.create(context, R.raw.beep);
        mp.start();
        mp.setOnCompletionListener(MediaPlayer::release);
    }

    public void startWebServer() {
        Log.d(TAG,"startWebServer");

        if (mWebServer != null)
            return;

        try {
            mWebServer = new GarminConnectIQWebServer();
            Log.d(TAG, "port is:" + mWebServer.getListeningPort());

            HashMap<Object, Object> data = new HashMap<>();
            data.put(MESSAGE_KEY_HTTP_PORT, mWebServer.getListeningPort());

            HashMap<Object, Object> message = new HashMap<>();
            message.put(MESSAGE_KEY_MSG_TYPE, MessageType.HTTP_READY.ordinal());
            message.put(MESSAGE_KEY_MSG_DATA, new MonkeyHash(data));

            try {
                mConnectIQ.sendMessage(mDevice, mMyApp, message, (device, app, status) -> {
                    Log.d(TAG, "message status: " + status.name());

                    if (!status.name().equals("SUCCESS"))
                        Toast.makeText(GarminConnectIQ.this, status.name(), Toast.LENGTH_LONG).show();
                });
            } catch (InvalidStateException e) {
                errors.add("ConnectIQ is not in a valid state");
                Toast.makeText(this, "ConnectIQ is not in a valid state", Toast.LENGTH_LONG).show();
            } catch (ServiceUnavailableException e) {
                errors.add("ConnectIQ service is unavailable.   Is Garmin Connect Mobile installed and running?");
                Toast.makeText(this, "ConnectIQ service is unavailable.   Is Garmin Connect Mobile installed and running?", Toast.LENGTH_LONG).show();
            }

        } catch (IOException ignored) {
        }
    }

    public void stopWebServer() {
        Log.d(TAG,"stopWebServer");
        if (mWebServer != null) {
            mWebServer.stop();
            mWebServer = null;
        }
    }
}

class GarminConnectIQWebServer extends NanoHTTPD {
    public GarminConnectIQWebServer()throws IOException {
        super("127.0.0.1", 0); // 0 to automatically find an available ephemeral port
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session)
    {
        if (session.getMethod() == Method.GET && session.getUri().equals("/data")) {
            return handleData((GarminConnectIQ)session);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Error 404, file not found.");
    }

    private Response handleData(GarminConnectIQ session) {
        Log.d("GarminConnectIQWebSe...","handleData");
        JSONObject data = new JSONObject();

        try {
            data.put("" + GarminConnectIQ.MESSAGE_KEY_SPEED, session.lastSpeed / 10); // Convert to km/h
            data.put("" + GarminConnectIQ.MESSAGE_KEY_BATTERY, session.lastBattery);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_TEMPERATURE, session.lastTemperature);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_FAN_STATE, session.lastFanStatus);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_BT_STATE, session.lastConnectionState);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_VIBE_ALERT, false);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_USE_MPH, session.lastUseMph);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_MAX_SPEED, session.lastMaxSpeed);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_RIDE_TIME, session.lastRideTime);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_DISTANCE, session.lastDistance / 100);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_TOP_SPEED, session.lastTopSpeed / 10);
            data.put("" + GarminConnectIQ.MESSAGE_KEY_POWER, session.lastPower);
            JSONObject message = new JSONObject();
            message.put("" + GarminConnectIQ.MESSAGE_KEY_MSG_TYPE, GarminConnectIQ.MessageType.EUC_DATA.ordinal());
            message.put("" + GarminConnectIQ.MESSAGE_KEY_MSG_DATA, data);

            return newFixedLengthResponse(Response.Status.OK, "application/json", message.toString());
        } catch (JSONException e) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "");
        }
    }
}