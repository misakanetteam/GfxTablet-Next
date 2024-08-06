package team.misakanet.gfxtablet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CanvasActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "GfxTablet.Canvas";

    final Uri homepageUri = Uri.parse(("https://gfxtablet.bitfire.at"));

    NetworkClient netClient;

    SharedPreferences preferences;
    boolean fullScreen = false;

    ActivityResultLauncher<String> imageSelectActivityResultLauncher;
    ActivityResultLauncher<Intent> intentActivityResultLauncher;

    ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        imageSelectActivityResultLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), result -> {
            if (result != null) {
                String[] filePathColumn = { MediaStore.Images.Media.DATA };

                try (Cursor cursor = getContentResolver().query(result, filePathColumn, null, null, null)) {
                    assert cursor != null;
                    cursor.moveToFirst();

                    int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
                    String picturePath = cursor.getString(columnIndex);

                    preferences.edit().putString(SettingsActivity.KEY_TEMPLATE_IMAGE, picturePath).apply();
                    showTemplateImage();
                }
            }
        });

        intentActivityResultLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{});

        setContentView(R.layout.activity_canvas);

        // create network client in a separate thread
        netClient = new NetworkClient(PreferenceManager.getDefaultSharedPreferences(this));
        new Thread(netClient).start();

        executorService = Executors.newFixedThreadPool(5);

        // notify CanvasView of the network client
        CanvasView canvas = findViewById(R.id.canvas);
        canvas.setNetworkClient(netClient);

        executorService.execute(new updateHost());
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (preferences.getBoolean(SettingsActivity.KEY_KEEP_DISPLAY_ACTIVE, true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        showTemplateImage();
    }

    @Override
    protected void onDestroy() {
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        netClient.getQueue().add(new NetEvent(NetEvent.Type.TYPE_DISCONNECT));

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_canvas, menu);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (fullScreen){
            switchFullScreen(null);
        }
        else
            super.onBackPressed();
    }

    public void showAbout(MenuItem item) {
        startActivity(new Intent(Intent.ACTION_VIEW, homepageUri));
    }

    public void showDonate(MenuItem item) {
        startActivity(new Intent(Intent.ACTION_VIEW, homepageUri.buildUpon().appendPath("donate").build()));
    }

    public void showSettings(MenuItem item) {
        intentActivityResultLauncher.launch(new Intent(this, SettingsActivity.class));
    }


    // preferences were changed
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        assert key != null;
        if (key.equals(SettingsActivity.KEY_PREF_HOST)) {
            Log.i(TAG, "Recipient host changed, reconfiguring network client");
            executorService.execute(new updateHost());
        }
    }


    // full-screen methods
    public void switchFullScreen(MenuItem item) {
        if (fullScreen) {
            fullScreen=false;
            Objects.requireNonNull(CanvasActivity.this.getSupportActionBar()).show();
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).show(WindowInsetsCompat.Type.systemBars());
        }
        else {
            fullScreen=true;
            Objects.requireNonNull(CanvasActivity.this.getSupportActionBar()).hide();
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).hide(WindowInsetsCompat.Type.systemBars());
            Toast.makeText(CanvasActivity.this, "Press Back button to leave full-screen mode.", Toast.LENGTH_LONG).show();
        }
    }

    // template image logic

    private String getTemplateImagePath() {
        return preferences.getString(SettingsActivity.KEY_TEMPLATE_IMAGE, null);
    }

    public void setTemplateImage(MenuItem item) {
        if (getTemplateImagePath() == null)
            selectTemplateImage(item);
        else {
            // template image already set, show popup
            PopupMenu popup = new PopupMenu(this, findViewById(R.id.menu_set_template_image));
            popup.getMenuInflater().inflate(R.menu.set_template_image, popup.getMenu());
            popup.show();
        }
    }

    public void selectTemplateImage(MenuItem item) {
        imageSelectActivityResultLauncher.launch("image/*");

    }

    public void clearTemplateImage(MenuItem item) {
        preferences.edit().remove(SettingsActivity.KEY_TEMPLATE_IMAGE).apply();
        showTemplateImage();
    }

    public void showTemplateImage() {
        ImageView template = findViewById(R.id.canvas_template);
        template.setImageDrawable(null);

        if (template.getVisibility() == View.VISIBLE) {
            String picturePath = preferences.getString(SettingsActivity.KEY_TEMPLATE_IMAGE, null);
            if (picturePath != null)
                try {
                    // TODO load bitmap efficiently, for intended view size and display resolution
                    // https://developer.android.com/training/displaying-bitmaps/load-bitmap.html
                    final Drawable drawable = new BitmapDrawable(getResources(), picturePath);
                    template.setImageDrawable(drawable);
                } catch (Exception e) {
                    Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                }
        }
    }

    private class updateHost implements Runnable {
        @Override
        public void run() {
            boolean rslt = netClient.reconfigureNetworking();

            Handler uiThread = new Handler(Looper.getMainLooper());
            uiThread.post(() -> {
                if (rslt)
                    Toast.makeText(CanvasActivity.this, "Touch events will be sent to " + netClient.destAddress.getHostAddress() + ":" + NetworkClient.GFXTABLET_PORT, Toast.LENGTH_LONG).show();

                findViewById(R.id.canvas_template).setVisibility(rslt ? View.VISIBLE : View.GONE);
                findViewById(R.id.canvas).setVisibility(rslt ? View.VISIBLE : View.GONE);
                findViewById(R.id.canvas_message).setVisibility(rslt ? View.GONE : View.VISIBLE);
            });
        }

    }
}
