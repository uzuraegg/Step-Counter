package stepcounter.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;

import java.text.DateFormat;
import java.util.Date;

import uk.ac.ox.eng.stepcounter.StepCounter;


public class MainActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    // sampling frequency in Hz
    private final int SAMPLING_FREQUENCY = 100;

    // Layout elements
    private TextView tv_stepCount;
    private TextView tv_HWSteps;
    private Button btn_toggleStepCounter;
    private TextView tv_latitude;
    private TextView tv_longitude;

    // Internal state
    private boolean isEnabled = false;

    // Step Counter objects
    private StepCounter stepCounter;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor hwstepsCounter;

    private int currentSteps = 0;
    private int lastSteps = -1;
    private int hwsteps = 0;

    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;

    private static final String TAG = MainActivity.class.getSimpleName();

    //位置情報の更新間隔。実際には多少頻度が多くなるかもしれない
    public static final long UPDATE_INTERVAL_IN_MILLISECONDS = 10000;

    //最速の更新間隔。この値より頻繁に更新されることはない。
    public static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2;

    private static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    private static final int REQUEST_CHECK_SETTINGS = 10;

    private Boolean mRequestingLocationUpdates;
    private Location mCurrentLocation;
    private String mLastUpdateTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep screen on.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tv_stepCount = (TextView) findViewById(R.id.stepsTextView);
        tv_HWSteps = (TextView) findViewById(R.id.hwstepsTextView);
        tv_latitude = (TextView) findViewById(R.id.latTextView);
        tv_longitude = (TextView) findViewById(R.id.lonTextView);
        btn_toggleStepCounter = (Button) findViewById(R.id.btn_toggleStepCounter);
        btn_toggleStepCounter.setOnClickListener(startClickListener);

        // Initialize step counter
        sensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        hwstepsCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepCounter = new StepCounter(SAMPLING_FREQUENCY);
        stepCounter.addOnStepUpdateListener(new StepCounter.OnStepUpdateListener() {
            @Override
            public void onStepUpdate(final int steps) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        currentSteps = steps;
                        String text = "歩数: " + Integer.toString(currentSteps);
                        tv_stepCount.setText(text);
                    }
                });
            }
        });
        stepCounter.setOnFinishedProcessingListener(new StepCounter.OnFinishedProcessingListener() {
            @Override
            public void onFinishedProcessing() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btn_toggleStepCounter.setEnabled(true);
                    }
                });
            }
        });

        mRequestingLocationUpdates = false;
        buildGoogleApiClient();
        startLocationUpdates();
    }

    protected synchronized void buildGoogleApiClient() {
        Log.i(TAG, "Building GoogleApiClient");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        createLocationRequest();
    }

    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
        mLocationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    private void startLocationUpdates() {
        Log.i(TAG, "startLocationUpdates");

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(mLocationRequest);
        // 現在位置の取得の前に位置情報の設定が有効になっているか確認する
        PendingResult<LocationSettingsResult> result =
                LocationServices.SettingsApi.checkLocationSettings(mGoogleApiClient, builder.build());
        result.setResultCallback(new ResultCallback<LocationSettingsResult>() {
            @Override
            public void onResult(@NonNull LocationSettingsResult locationSettingsResult) {
                final Status status = locationSettingsResult.getStatus();

                switch (status.getStatusCode()) {
                    case LocationSettingsStatusCodes.SUCCESS:
                        // 設定が有効になっているので現在位置を取得する
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, MainActivity.this);
                        }
                        break;
                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        // 設定が有効になっていないのでダイアログを表示する
                        try {
                            status.startResolutionForResult(MainActivity.this, 10);
                        } catch (IntentSender.SendIntentException e) {
                            // Ignore the error.
                        }
                        break;
                    case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                        // Location settings are not satisfied. However, we have no way
                        // to fix the settings so we won't show the dialog.
                        break;
                }
            }
        });
    }

    private View.OnClickListener startClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (isEnabled) {
                // Stop sampling
                sensorManager.unregisterListener(accelerometerEventListener);
                if(hwstepsCounter != null) sensorManager.unregisterListener(hwStepsEventListener);

                // Stop algorithm.
                isEnabled = false;
                btn_toggleStepCounter.setEnabled(false);
                btn_toggleStepCounter.setText("Start Step Counting");
                stepCounter.stop();

            } else {
                // Start algorithm.
                tv_stepCount.setText("歩数: 0");
                if(hwstepsCounter != null)tv_HWSteps.setText("歩数(歩数センサ): 0");
                isEnabled = true;
                currentSteps = 0;
                hwsteps = 0;
                lastSteps = -1;
                stepCounter.start();
                btn_toggleStepCounter.setText("Stop Step Counting");

                // Start sampling
                int periodusecs = (int) (1E6 / SAMPLING_FREQUENCY);
                Log.d(MainActivity.class.getSimpleName(), "Sampling at " + periodusecs + " usec");
                sensorManager.registerListener(accelerometerEventListener, accelerometer, periodusecs);
                if(hwstepsCounter != null) sensorManager.registerListener(hwStepsEventListener, hwstepsCounter, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    };

    private SensorEventListener accelerometerEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            stepCounter.processSample(event.timestamp, event.values);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private SensorEventListener hwStepsEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            int steps = (int)event.values[0];
            if(lastSteps == -1){
                hwsteps = 0;
                lastSteps = steps;
            } else {
                hwsteps = steps - lastSteps;
            }
            Log.d(MainActivity.class.getSimpleName(), "歩数(歩数センサ): " + steps + ", i.e. " + hwsteps);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String hwstr = "歩数(歩数センサ): " + hwsteps;
                    tv_HWSteps.setText(hwstr);
                }
            });
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    };

    public static boolean isPlayServicesAvailable(Context context) {
        // Google Play Service APKが有効かどうかチェックする
        int resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        if (resultCode != ConnectionResult.SUCCESS) {
            GoogleApiAvailability.getInstance().getErrorDialog((Activity) context, resultCode, 2).show();
            return false;
        }
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CHECK_SETTINGS:
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        startLocationUpdates();
                        break;
                    case Activity.RESULT_CANCELED:
                        break;
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLocationUpdates();
                } else {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                        mRequestingLocationUpdates = false;
                        Toast.makeText(MainActivity.this, "このアプリの機能を有効にするには端末の設定画面からアプリの位置情報パーミッションを有効にして下さい。", Toast.LENGTH_SHORT).show();
                    } else {
                        showRationaleDialog();
                    }
                }
                break;
            }
        }
    }

    private void showRationaleDialog() {
        new AlertDialog.Builder(this)
                .setPositiveButton("許可する", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
                    }
                })
                .setNegativeButton("しない", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(MainActivity.this, "位置情報パーミッションが許可されませんでした。", Toast.LENGTH_SHORT).show();
                        mRequestingLocationUpdates = false;
                    }
                })
                .setCancelable(false)
                .setMessage("このアプリは位置情報の利用を許可する必要があります。")
                .show();
    }


    @Override
    protected void onStart() {
        Log.i(TAG, "onStart");
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    public void onResume() {
        super.onResume();
        isPlayServicesAvailable(this);

        // Within {@code onPause()}, we pause location updates, but leave the
        // connection to GoogleApiClient intact.  Here, we resume receiving
        // location updates if the user has requested them.

        if (mGoogleApiClient.isConnected() && mRequestingLocationUpdates) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        if (mGoogleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mGoogleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
        }
        mGoogleApiClient.disconnect();

        super.onStop();
    }

    //GoogleApiClientのオーバーライドメソッド
    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.i(TAG, "onConnected");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (mCurrentLocation == null) {
            mCurrentLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
            mLastUpdateTime = DateFormat.getTimeInstance().format(new Date());
            //updateUI();
            tv_latitude.setText(String.format("緯度: %f", mCurrentLocation.getLatitude()));
            tv_longitude.setText(String.format("経度: %f", mCurrentLocation.getLongitude()));
        }

        if (mRequestingLocationUpdates) {
            startLocationUpdates();
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        // The connection to Google Play services was lost for some reason. We call connect() to
        // attempt to re-establish the connection.
        Log.i(TAG, "Connection suspended");
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        // Refer to the javadoc for ConnectionResult to see what error codes might be returned in
        // onConnectionFailed.
        Log.i(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + connectionResult.getErrorCode());
    }

    //LocationListenerのオーバーライドメソッド
    @Override
    public void onLocationChanged(Location location) {
        Log.i(TAG, "onLocationChanged");
        mCurrentLocation = location;
        mLastUpdateTime = DateFormat.getTimeInstance().format(new Date());
        //updateUI();
        tv_latitude.setText(String.format("緯度: %f", mCurrentLocation.getLatitude()));
        tv_longitude.setText(String.format("経度: %f", mCurrentLocation.getLongitude()));
    }
}
