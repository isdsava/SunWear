/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package au.com.fintechapps.sunface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private GoogleApiClient mGoogleApiClient;
    private static final String FORECAST_PATH="/forecast";
    private static final String HIGH_KEY ="high";
    private static final String LOW_KEY ="low";
    private static final String IMAGE_KEY ="image";
    private static final String DATE_KEY="date";
    private String mHigh=null;
    private String mLow=null;
    private String mDate=null;
    private Bitmap meBitmap=null;
    private Rect mBos = new Rect();


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);


            mGoogleApiClient = new GoogleApiClient.Builder(SunWatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();



            setWatchFaceStyle(new WatchFaceStyle.Builder(SunWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }
        @Override
        public void onConnected(@Nullable Bundle bundle) {

            Log.d("ME HERE", "GotMeConnection:");

            Wearable.DataApi.addListener(mGoogleApiClient,Engine.this);
           //TODO removed as this gets the dodo but doesnt wait for the listener
            // Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(onConnectedCallback);

        }

        private final ResultCallback<DataItemBuffer> onConnectedCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(@NonNull DataItemBuffer dataItems) {
                Log.d("HHHH", "me here");
                    int items = dataItems.getCount();

                Log.d("HHHH", String.valueOf(items));
                    //for (int i=0;i<= items;i++){
                      //  Log.d("HHHH", dataItems.get(i).getAssets().toString());

                    //}

            }
        };

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {

            // TODO http://stackoverflow.com/questions/25141046/wearablelistenerservice-ondatachanged-is-not-called
            // http://stackoverflow.com/questions/24676165/unable-to-push-data-to-android-wear-emulator

            Log.d("SUNFACESERVICE", "I at least got called");

                    for (DataEvent dataEvent : dataEventBuffer){

                        if(dataEvent.getType() == DataEvent.TYPE_CHANGED){
                                String path = dataEvent.getDataItem().getUri().getPath();
                                if(FORECAST_PATH.equals(path)){
                                    Log.v("ITHASPPED",dataEvent.getDataItem().toString());
                                    DataMapItem  dataMapItem = DataMapItem.fromDataItem(dataEvent.getDataItem());
                                            mHigh = dataMapItem.getDataMap().getString(HIGH_KEY);
                                            mLow =  dataMapItem.getDataMap().getString(LOW_KEY);
                                            mDate =  dataMapItem.getDataMap().getString(DATE_KEY);
                                    Asset mePhoto = dataMapItem.getDataMap().getAsset(IMAGE_KEY);
                                        new LoadBitmapAsync().execute(mePhoto);
                                    Log.v("HAPPYHIGH",mHigh);
                                }


                        }

                    }


        }


        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override //This will help with any background image
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {

            Log.d("ME HERE", "onVisibilityChanged: " + visible);

            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient,this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }


        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunWatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            //reset
            mTextPaint =createTextPaint(getResources().getColor(R.color.digital_text));
            mTextPaint.setTextSize(getResources().getDimension(R.dimen.digital_text_size));

            int fudge=0;
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            mTextPaint.getTextBounds(text,0,text.length(),mBos);

            canvas.drawText(text, (bounds.width()-mBos.width())/2, mYOffset, mTextPaint);

                Rect rectum = canvas.getClipBounds();

                    Log.v("RECUM",String.valueOf(rectum.width()));

            if(mDate!=null) {
                mTextPaint.setColor(getResources().getColor(R.color.primary_light));
                mTextPaint.setTextSize(getResources().getDimension(R.dimen.digital_text_small));
                mTextPaint.getTextBounds(mDate, 0, mDate.length(), mBos);
                //TODO clean up mOxfest etrcs
                canvas.drawText(mDate, (bounds.width() - mBos.width()) / 2, mYOffset + 36, mTextPaint);
            }

            mTextPaint.setColor(getResources().getColor(R.color.primary_light));
            canvas.drawLine(((bounds.width()/2)-30),mYOffset+56,((bounds.width()/2)+30),mYOffset+56,mTextPaint);

            if(mHigh!=null){
                mTextPaint.setColor(getResources().getColor(R.color.digital_text));
                mTextPaint.setTextSize(getResources().getDimension(R.dimen.digital_text_med));
                mTextPaint.setTypeface(BOLD_TYPEFACE);
                mTextPaint.getTextBounds(mHigh,0,mHigh.length(),mBos);
                int highW = mBos.width();
                float highH= mBos.height() + 76 + mYOffset;
                mXOffset = (bounds.width()/2)-highW/2;
                canvas.drawText(mHigh,mXOffset,highH,mTextPaint);

                mTextPaint.setColor(getResources().getColor(R.color.primary_light));
                mTextPaint.setTextSize(getResources().getDimension(R.dimen.digital_text_med));
                mTextPaint.setTypeface(NORMAL_TYPEFACE);
                mTextPaint.getTextBounds(mLow,0,mLow.length(),mBos);
                mXOffset = (bounds.width()/2) + (highW/2) + 20;
                canvas.drawText(mLow,mXOffset,highH,mTextPaint);

                Rect tum = new Rect();
                canvas.getClipBounds(tum);
                Log.d("TUM",String.valueOf(tum.width()));
                Log.d("Bound",String.valueOf(bounds.width()));

                if(meBitmap!=null){
                    mXOffset = (bounds.width()/2) - (highW/2) - 20 - meBitmap.getWidth();

                    canvas.drawBitmap(meBitmap,mXOffset,highH - (meBitmap.getHeight()/2)-8,null);

                }
            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }



        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private class LoadBitmapAsync extends AsyncTask<Asset,Void,Bitmap> {
            @Override
            protected Bitmap doInBackground(Asset... assets) {

                if(assets.length>0){
                    Asset asset = assets[0];
                    InputStream inputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient,asset).await().getInputStream();

                    if(inputStream!=null){
                        return BitmapFactory.decodeStream(inputStream);
                    }else{return null;}
                }else {return null;}

            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if(bitmap!=null){

                    meBitmap = Bitmap.createScaledBitmap(bitmap,42,42,false);
                    invalidate();
                }
            }
        }

    }
}
