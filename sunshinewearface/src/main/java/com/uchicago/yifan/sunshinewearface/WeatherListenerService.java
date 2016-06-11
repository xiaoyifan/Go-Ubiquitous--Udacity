package com.uchicago.yifan.sunshinewearface;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Created by Yifan on 6/11/16.
 */
public class WeatherListenerService extends WearableListenerService implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    private static final String TAG = "WearableListenerService";

    private GoogleApiClient mGoogleApiClient;

    private static final String HIGH_TEMP_KEY = "hightemp";
    private static final String LOW_TEMP_KEY = "lowtemp";
    private static final String IMAGE_TEMP_KEY = "imagetemp";
    private static final String TIME_STAMP_KEY = "timestamp";

    private static final String START_WEATHER_SYNC_PATH = "/pleasesync";
    private static final String CURRENT_TEMP_PATH = "/currenttemp";
    private static final String TIME_STAMP_PATH = "/timestamp";

    String mHigh = "";
    String mLow = "";
    Bitmap mBitmap = null;

    @Override
    public void onCreate() {
        super.onCreate();

        LOGD(TAG, "onCreate: ");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();

    }


    @Override
    public void onPeerConnected(Node node) {
        super.onPeerConnected(node);
        LOGD(TAG, "onPeerConnected: " + node);
    }

    @Override
    public void onPeerDisconnected(Node node) {
        super.onPeerDisconnected(node);
        LOGD(TAG, "onPeerDisconnected: " + node);
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        // Check to see if the message is to sync weather
        if (messageEvent.getPath().equals(START_WEATHER_SYNC_PATH)) {
            LOGD(TAG, "onMessageReceived: " + START_WEATHER_SYNC_PATH);
            connectGoogleApiAgain();
            // Broadcast here too, as sometimes watchface doesnt get loaded with data
            // sends message only when watchFace hasnt received it before by checking if bitmap is null
            sendMessage(messageEvent.getSourceNodeId());
        }

    }

    private void sendMessage(String nodeId) {
        Intent intent = new Intent("weatherProcessed");
        intent.putExtra("highTemp", mHigh);
        intent.putExtra("lowTemp", mLow);
        try { // convert bitmap to file // This is the best practice http://stackoverflow.com/a/11010565
            //Write file
            if(mBitmap != null) {
                String filename = "bitmap.png";
                FileOutputStream stream = this.openFileOutput(filename, Context.MODE_PRIVATE);
                mBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); //  (Bitmap.CompressFormat format, int quality, OutputStream stream)

                //Cleanup
                stream.close();
                //            bmp.recycle();

                intent.putExtra("bitmapFilename", filename);
                LOGD(TAG, "Broadcasting message");
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            }
            else{
                if(nodeId != null) {
                    LOGD(TAG, "sendMessage: " + "null bitmap so sent message to handheld to send me again");
                    // Send the rpc saying that we have null bitmap here!! Alert
                    Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, "/itsnull",
                            new byte[0]);
                }
            }
        } catch (Exception e) {
            LOGD(TAG, "sendMessage: " + "bitmap file not created : " + e.toString());
            e.printStackTrace();
        }

    }

    private void sendMessage() {
        sendMessage(null);
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        super.onDataChanged(dataEventBuffer);

        LOGD(TAG, "onDataChanged: " + dataEventBuffer);
        connectGoogleApiAgain();
        // Loop through the events and send a message back to the node that created the data item.
        for (DataEvent event : dataEventBuffer) {
            Uri uri = event.getDataItem().getUri();
            String path = uri.getPath();
            if (CURRENT_TEMP_PATH.equals(path)) {
                DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                mHigh = dataMap.getString(HIGH_TEMP_KEY);
                mLow = dataMap.getString(LOW_TEMP_KEY);
                // Loads image on background thread.
                new LoadBitmapAsyncTask().execute(dataMap.getAsset(IMAGE_TEMP_KEY));
                LOGD(TAG, "GOT! - Max Temp=" + mHigh + "; Min Temp=" + mLow);
            } else if(TIME_STAMP_PATH.equals(path)) {
                DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                mHigh = dataMap.getString(HIGH_TEMP_KEY);
                mLow = dataMap.getString(LOW_TEMP_KEY);
                dataMap.getString(TIME_STAMP_KEY);
                // Loads image on background thread.
                new LoadBitmapAsyncTask().execute(dataMap.getAsset(IMAGE_TEMP_KEY));
                LOGD(TAG, "GOT after null! - Max Temp=" + mHigh + "; Min Temp=" + mLow);
            }
        }
    }

    private void connectGoogleApiAgain(){
        // connect if not connected
        if (!mGoogleApiClient.isConnected()) {
            ConnectionResult connectionResult =
                    mGoogleApiClient.blockingConnect(30, TimeUnit.SECONDS);

            if (!connectionResult.isSuccess()) {
                Log.e(TAG, "DataLayerListenerService failed to connect to GoogleApiClient, "
                        + "error code: " + connectionResult.getErrorCode());
            }
        }
    }

    private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

        @Override
        protected Bitmap doInBackground(Asset... params) {

            if(params.length > 0) {

                Asset asset = params[0];

                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                return BitmapFactory.decodeStream(assetInputStream);

            } else {
                Log.e(TAG, "Asset must be non-null");
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {

            if (bitmap != null) {
                LOGD(TAG, "onPostExecute");
                mBitmap = bitmap;
                sendMessage();
            }
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(TAG, "Google API Client was connected");
        Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "Connection to Google API client was suspended");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.e(TAG, "Connection to Google API client has failed");
        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        mGoogleApiClient.connect();
    }

    private static void LOGD(final String tag, String message) {
        Log.d(tag, message);
    }
}
