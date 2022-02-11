package online.justvpn;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;


import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity
{
    final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
    final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
    private boolean mAllowedOverlay = false;

    ActivityResultLauncher<Intent> mDrawOverlaylauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result ->
            {
                mAllowedOverlay = Settings.canDrawOverlays(this);
                SwitchMaterial s = (SwitchMaterial)findViewById(R.id.autostartSwitch);
                s.setChecked(mAllowedOverlay);
            });

    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);
        updateUserPreferences();
        mAllowedOverlay = Settings.canDrawOverlays(this);

        findViewById(R.id.rememberServerSwitch).setOnClickListener(view ->
        {
            SwitchMaterial s = (SwitchMaterial) view;
            SharedPreferences settings = getSharedPreferences("preferences", 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("rememberserver", s.isChecked());

            // if this settings is disabled, autostart is not possible, so disable it too
            if (!s.isChecked())
            {
                editor.putBoolean("autostart", false);
                SwitchMaterial autoStartSwitch = (SwitchMaterial)findViewById(R.id.autostartSwitch);
                autoStartSwitch.setChecked(false);
            }

            editor.commit();
        });

        findViewById(R.id.autoconnectSwitch).setOnClickListener(view ->
        {
            SwitchMaterial s = (SwitchMaterial) view;
            SharedPreferences settings = getSharedPreferences("preferences", 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("autoconnect", s.isChecked());
            editor.commit();
        });

        findViewById(R.id.autostartSwitch).setOnClickListener(view ->
        {
            SwitchMaterial s = (SwitchMaterial) view;
            if (s.isChecked())
            {
                checkPermission();
                if (mAllowedOverlay)
                {
                    // Must be enabled in order to autoconnect to vpn at bootup
                    SwitchMaterial rememberSwitch = (SwitchMaterial)findViewById(R.id.rememberServerSwitch);
                    rememberSwitch.setChecked(true);
                    SharedPreferences settings = getSharedPreferences("preferences", 0);
                    SharedPreferences.Editor editor = settings.edit();
                    editor.putBoolean("rememberserver", true);
                    editor.commit();
                }
            }

            SharedPreferences settings = getSharedPreferences("preferences", 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("autostart", s.isChecked());
            editor.commit();
        });
    }
    void updateUserPreferences()
    {
        SharedPreferences settings = getSharedPreferences("preferences", 0);
        boolean rememberserver = settings.getBoolean("rememberserver", false);
        boolean autoconnect = settings.getBoolean("autoconnect", false);
        boolean autostart = settings.getBoolean("autostart", false);
        ((SwitchMaterial)findViewById(R.id.rememberServerSwitch)).setChecked(rememberserver);
        ((SwitchMaterial)findViewById(R.id.autoconnectSwitch)).setChecked(autoconnect);
        ((SwitchMaterial)findViewById(R.id.autostartSwitch)).setChecked(autostart);
    }

    public void checkPermission()
    {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);

        Bundle bundle = new Bundle();
        String showArgs = getPackageName() + "/" + JustVpnService.class.getName();
        bundle.putString(EXTRA_FRAGMENT_ARG_KEY, showArgs);
        intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, showArgs);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle);

        if (intent != null)
        {
            if (!Settings.canDrawOverlays(this))
            {
                mDrawOverlaylauncher.launch(intent);
                String toastText = "Select justvpn here and allow overlay permission";
                Toast.makeText(getApplicationContext(), toastText, Toast.LENGTH_LONG).show();
            }
        }
    }

}
