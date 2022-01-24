package online.justvpn;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity
{
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);
        updateUserPreferences();

        findViewById(R.id.rememberServerSwitch).setOnClickListener(view ->
        {
            SwitchMaterial s = (SwitchMaterial) view;
            SharedPreferences settings = getSharedPreferences("preferences", 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("rememberserver", s.isChecked());
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

}
