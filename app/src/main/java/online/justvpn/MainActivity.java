package online.justvpn;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import online.justvpn.databinding.ActivityMainBinding;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ListView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.contract.ActivityResultContracts;
import android.app.Activity;
import android.net.VpnService;
import android.widget.Switch;

public class MainActivity extends AppCompatActivity
{
    private ActivityMainBinding binding;
    private boolean mUserVpnAllowed = false;

    private void requestVpnServicePermissionDialog()
    {
        // Ask for vpn service permission
        Intent dialog = VpnService.prepare(getApplicationContext());
        if (dialog != null)
        {
            ActivityResultLauncher<Intent> VpnServiceActivityResultLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK)
                        {
                            mUserVpnAllowed = true;
                        }
                    });
            VpnServiceActivityResultLauncher.launch(dialog);
        }
        else
        {
            // already permitted
            mUserVpnAllowed = true;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        // update servers list
        binding.pullToRefresh.setOnRefreshListener(() -> getServersAvailable());

        binding.pullToRefresh.setRefreshing(true);
        getServersAvailable();

        requestVpnServicePermissionDialog();

        binding.serversListView.setOnItemClickListener((adapterView, view, i, l) ->
                processServerItemSelected(adapterView, view, i, l));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void processServerItemSelected(AdapterView<?> adapterView, View view, int i, long l)
    {
        if (!mUserVpnAllowed)
        {
            requestVpnServicePermissionDialog();
            return;
        }

        Switch selected = view.findViewById(R.id.enableSwitch);

        Intent service = new Intent(getApplicationContext(), JustVpnService.class);

        // Uncheck all but selected
        for (int b = 0; b < binding.serversListView.getChildCount(); b++)
        {
            View child = binding.serversListView.getChildAt(b);
            Switch toDisable = child.findViewById(R.id.enableSwitch);
            if (!selected.equals(toDisable))
            {
                // disconnect any previous connection
                if (toDisable.isChecked())
                {
                    startForegroundService(service.setAction(JustVpnService.ACTION_DISCONNECT));
                }
                toDisable.setChecked(false);
            }
        }

        // disconnect
        if (selected.isChecked())
        {
            selected.setChecked(false);
            startForegroundService(service.setAction(JustVpnService.ACTION_DISCONNECT));
        }
        // connect
        else
        {
            selected.setChecked(true);
            ServerListItemDataModel model = (ServerListItemDataModel)adapterView.getItemAtPosition(i);

            service.putExtra("ip", model.get_ip());
            startForegroundService(service.setAction(JustVpnService.ACTION_CONNECT));
        }
    }

    private void getServersAvailable()
    {
        // Download servers list
        RequestQueue queue = Volley.newRequestQueue(this);
        String url = "http://justvpn.online/api/getservers";

        // Request a string response from the provided URL.
        StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                response ->
                {
                    ArrayList<ServerListItemDataModel> dataModels = new ArrayList<>();
                    try {
                        JSONArray jArray = new JSONArray(response);
                        for (int i = 0; i < jArray.length(); i++)
                        {
                            JSONArray serverArr = jArray.getJSONArray(i);
                            String ip = serverArr.get(0).toString();
                            String country = serverArr.get(1).toString();
                            dataModels.add(new ServerListItemDataModel(ip, country));
                        }

                        binding.serversListView.setAdapter(new ServerListItemAdaptor(getApplicationContext(), dataModels));
                    } catch (JSONException e)
                    {
                        e.printStackTrace();
                    }
                }, error -> {
                    // TODO: Show messagebox
                }
        );

        // Add the request to the RequestQueue.
        queue.add(stringRequest);

        binding.pullToRefresh.setRefreshing(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}