package online.justvpn;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;

import online.justvpn.databinding.ActivityMainBinding;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.DialogFragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.app.Activity;
import android.net.VpnService;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity implements SubscribeDialog.NoticeDialogListener
{
    private ActivityMainBinding binding;
    private boolean mUserVpnAllowed = false;
    public Activity mActivity;
    private boolean mProUser = false;
    private BillingClient mBillingClient;
    private Timer mUpdateInfoTimer;
    private boolean mBillingActive = false;
    private String mSubscriptionToken = "";
    private int mPendingHttpRequests = 0;

    private PurchasesUpdatedListener purchasesUpdatedListener = new PurchasesUpdatedListener() {
        @Override
        public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases)
        {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && purchases != null)
            {
                for (Purchase purchase : purchases)
                {
                    handlePurchase(purchase);
                }
            } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED)
            {
                // Handle an error caused by a user cancelling the purchase flow.
            }
            else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
            {
                for (Purchase purchase : purchases)
                {
                    handlePurchase(purchase);
                }
            }
            else {
                // Handle any other error codes.
            }
        }
    };

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            // Get extra data included in the Intent
            String status = intent.getStringExtra("Status");

            if (status == null)
            {
                return;
            }

            if (status.contains("failed"))
            {
                // connection attempt was rejected
                // Uncheck all
                for (int b = 0; b < binding.serversListView.getChildCount(); b++)
                {
                    View child = binding.serversListView.getChildAt(b);
                    Switch toDisable = child.findViewById(R.id.enableSwitch);
                    toDisable.setChecked(false);
                }
            }
            else if (status.contains("noslots"))
            {
                Toast.makeText(getApplicationContext(), R.string.noslots, Toast.LENGTH_LONG).show();
            }
            else if (status.contains("connected"))
            {
                // service notified about active connection, update switch -> enabled
                String serverIp = status.split(":")[1];

                for (int i = 0; i < binding.serversListView.getChildCount(); i++)
                {
                    View child = binding.serversListView.getChildAt(i);
                    TextView ipView = child.findViewById(R.id.textViewIP);
                    String ip = (String) ipView.getText();

                    if (ip.equals(serverIp))
                    {
                        Switch toEnable = child.findViewById(R.id.enableSwitch);
                        toEnable.setChecked(true);

                        // save last connected server to settings
                        SharedPreferences settings = getSharedPreferences("preferences", 0);
                        boolean rememberserver = settings.getBoolean("rememberserver", false);
                        if (rememberserver)
                        {
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putString("lastConnectedServer", ip);
                            editor.commit();
                        }
                    }
                }
            }
        }
    };

    @Override
    public void onDialogPositiveClick(DialogFragment dialog)
    {
        // User touched the dialog's ok button
        subscribe();
    }

    @Override
    public void onDialogNegativeClick(DialogFragment dialog)
    {
        // User touched the dialog's negative button
        int i = 0;
        i++;
    }

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
        mActivity = this;
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        // update servers list
        binding.pullToRefresh.setOnRefreshListener(() ->
        {
            getServersAvailable(false);
            updateInfo();
        });

        binding.pullToRefresh.setRefreshing(true);
        getServersAvailable(true);

        requestVpnServicePermissionDialog();

        binding.serversListView.setOnItemClickListener(this::processServerItemSelected);

        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter("JustVpnMsg"));

        // Establish google play connection
        mBillingClient = BillingClient.newBuilder(getApplicationContext())
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build();

        mBillingClient.startConnection(new BillingClientStateListener()
        {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult)
            {
                if (billingResult.getResponseCode() ==  BillingClient.BillingResponseCode.OK)
                {
                    // See if user has been subscribed
                    mBillingActive = true;
                    checkSubscription();
                }
            }
            @Override
            public void onBillingServiceDisconnected()
            {
                mBillingActive = false;
            }
        });

        mUpdateInfoTimer = new Timer();
        mUpdateInfoTimer.schedule(new TimerTask()
        {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void run()
            {
                updateInfo();
            }
        }, TimeUnit.SECONDS.toMillis(60), TimeUnit.SECONDS.toMillis(60));
        updateInfo();
    }

    void updateInfo()
    {
        RequestQueue queue = Volley.newRequestQueue(getApplicationContext());
        String get_info = "http://justvpn.online/api/info";
        StringRequest signalRequest = new StringRequest(Request.Method.GET, get_info,
                resp ->
                {
                    try
                    {
                        JSONArray jArray = new JSONArray(resp);
                        TextView tv = findViewById(R.id.infoTextView);
                        tv.setText("");
                        for (int i = 0; i < jArray.length(); i++)
                        {
                            JSONObject jsonObject = jArray.getJSONObject(i);
                            if (jsonObject.has("id") && jsonObject.has("text"))
                            {
                                String text = jsonObject.getString("text");
                                tv.append("* " + text + "\n");
                                tv.append("\n");
                            }
                        }
                    }
                    catch (JSONException e)
                    {
                        e.printStackTrace();
                    }
                    mPendingHttpRequests--;
                }
                , error ->
                {
                    mPendingHttpRequests--;
                    // not handled
                });
                queue.add(signalRequest);
                mPendingHttpRequests++;
    }

    void checkSubscription()
    {
        mBillingClient.queryPurchasesAsync(BillingClient.SkuType.SUBS, (billingResult, list) ->
        {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK)
            {
                for (Purchase p : list)
                {
                    try {
                        JSONObject purchase = new JSONObject(p.getOriginalJson());
                        if (purchase.getString("productId").equals("pro"))
                        {
                            mProUser = true;
                            mSubscriptionToken = purchase.getString("purchaseToken");
                            break;
                        }
                    } catch (JSONException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    void subscribe()
    {
        List<String> skuList = new ArrayList<> ();
        skuList.add("pro");
        SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
        params.setSkusList(skuList).setType(BillingClient.SkuType.SUBS);
        mBillingClient.querySkuDetailsAsync(params.build(),
                (billingResult1, skuDetailsList) ->
                {
                    if (skuDetailsList == null)
                    {
                        Toast.makeText(getApplicationContext(), R.string.needaccount, Toast.LENGTH_LONG).show();
                        return;
                    }
                    for (SkuDetails skDetail : skuDetailsList)
                    {
                        String title = skDetail.getTitle();
                        if (title.contains("pro"))
                        {
                            // Process the result.
                            BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                                    .setSkuDetails(skuDetailsList.get(0))
                                    .build();
                            int responseCode = mBillingClient.launchBillingFlow(mActivity, billingFlowParams).getResponseCode();
                            break;
                        }
                    }
                });
    }

    void handlePurchase(Purchase purchase)
    {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED)
        {
            if (!purchase.isAcknowledged())
            {
                AcknowledgePurchaseParams acknowledgePurchaseParams =
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build();
                mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult ->
                {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK)
                    {
                        runOnUiThread(() ->
                        {
                            Toast.makeText(getApplicationContext(),R.string.thanksforsubscribing, Toast.LENGTH_LONG).show();
                            // Verify whether the subscription is active
                            checkSubscription();
                        });
                    }
                });
            }
        }
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
                    startJustVpnServiceWithAction(JustVpnService.ACTION_DISCONNECT, "");
                }
                toDisable.setChecked(false);
            }
        }

        // disconnect
        if (selected.isChecked())
        {
            selected.setChecked(false);
            startJustVpnServiceWithAction(JustVpnService.ACTION_DISCONNECT, "");
        }
        // connect
        else
        {
            if (true) // TODO: is PRO server
            {
                if (!mProUser)
                {
                    SubscribeDialog dialog = new SubscribeDialog();
                    dialog.show(getSupportFragmentManager(), "");
                }
                else
                {
                    // Start connection
                    selected.setChecked(true);
                    ServerListItemDataModel model = (ServerListItemDataModel)adapterView.getItemAtPosition(i);
                    startJustVpnServiceWithAction(JustVpnService.ACTION_CONNECT, model.get_ip());
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startJustVpnServiceWithAction(String sAction, String sServerAddress)
    {
        Intent service = new Intent(getApplicationContext(), JustVpnService.class);
        service.putExtra("subscriptionToken", mSubscriptionToken);

        if (sAction.equals(JustVpnService.ACTION_CONNECT))
        {
            service.putExtra("ip", sServerAddress);
        }
        startForegroundService(service.setAction(sAction));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void applyUserSettings()
    {
        SharedPreferences settings = getSharedPreferences("preferences", 0);
        boolean autoconnect = settings.getBoolean("autoconnect", false);

        // User hasn't set autoconnect, nothing to do
        if (!autoconnect)
        {
            return;
        }

        boolean rememberserver = settings.getBoolean("rememberserver", false);

        String sServer = "";

        if (rememberserver)
        {
            sServer = settings.getString("lastConnectedServer", "");
        }
        else // Select server automatically depending on signal
        {
            ServerListItemDataModel model = new ServerListItemDataModel(0, "", "", Sig.UNKNOWN);
            for (int i = 0; i < binding.serversListView.getChildCount(); i++)
            {
                View child = binding.serversListView.getChildAt(i);
                ServerListItemDataModel tmpModel = (ServerListItemDataModel) binding.serversListView.getAdapter().getItem(i);
                if (tmpModel.get_signal().ordinal() < model.get_signal().ordinal())
                {
                    model = tmpModel;
                }
            }
            // Now we've found best server by signal, connect to it
            if (!model.get_signal().equals(Sig.UNKNOWN))
            {
                sServer = model.get_ip();
            }
        }

        if (!sServer.isEmpty())
        {
            startJustVpnServiceWithAction(JustVpnService.ACTION_CONNECT, sServer);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void getServersAvailable(Boolean bApplyUserSettings)
    {
        // Download servers list
        RequestQueue queue = Volley.newRequestQueue(this);
        String getServers = "http://justvpn.online/api/getservers";

        // Request a string response from the provided URL.
        StringRequest stringRequest = new StringRequest(Request.Method.GET, getServers,
                response ->
                {
                    ArrayList<ServerListItemDataModel> dataModels = new ArrayList<>();
                    try {
                        JSONArray jArray = new JSONArray(response);
                        for (int i = 0; i < jArray.length(); i++)
                        {
                            JSONObject jObject = jArray.getJSONObject(i);

                            if (jObject.has("id") &&
                                jObject.has("ip") &&
                                jObject.has("country") && jObject.has("active"))
                            {
                                if (jObject.getBoolean("active")) // Only advertise servers that are active
                                {
                                    int id = Integer.valueOf(jObject.get("id").toString());
                                    String ip = jObject.get("ip").toString();
                                    String country = jObject.get("country").toString();
                                    ServerListItemDataModel dataModel = new ServerListItemDataModel(id, ip, country, Sig.UNKNOWN);
                                    dataModels.add(dataModel);

                                    // request number of connections for each server to calculate signal for each server
                                    String serverId = jObject.get("id").toString();
                                    String getConnections = "http://justvpn.online/api/connections?serverid=" + serverId;
                                    StringRequest signalRequest = new StringRequest(Request.Method.GET, getConnections,
                                            resp ->
                                            {
                                                try
                                                {
                                                    JSONObject jObjectSig = new JSONObject(resp);
                                                    if (jObjectSig.has("connections"))
                                                    {
                                                        ServerListItemDataModel m = null;
                                                        for (int z = 0; z < binding.serversListView.getAdapter().getCount(); z++)
                                                        {
                                                            ServerListItemDataModel tmpModel = (ServerListItemDataModel) binding.serversListView.getAdapter().getItem(z);

                                                            if (tmpModel.get_id() == Integer.parseInt(serverId))
                                                            {
                                                                m = tmpModel;
                                                            }
                                                        }

                                                        if (m != null)
                                                        {
                                                            int connections = jObjectSig.getInt("connections");
                                                            if (connections >= 75)
                                                            {
                                                                m.set_signal(Sig.LOW);
                                                            }
                                                            else if (connections >= 50)
                                                            {
                                                                m.set_signal(Sig.MID);
                                                            }
                                                            else if (connections >= 25)
                                                            {
                                                                m.set_signal(Sig.GOOD);
                                                            }
                                                            else if (connections >= 0)
                                                            {
                                                                m.set_signal(Sig.BEST);
                                                            }
                                                        }
                                                    }
                                                } catch (JSONException e)
                                                {
                                                    e.printStackTrace();
                                                }
                                                mPendingHttpRequests--;
                                            }
                                            , error ->
                                            {
                                                // not handled
                                                mPendingHttpRequests--;
                                            });
                                    queue.add(signalRequest);
                                    mPendingHttpRequests++;
                                }
                            }
                        }

                        binding.serversListView.setAdapter(new ServerListItemAdaptor(getApplicationContext(), dataModels));
                        binding.pullToRefresh.setRefreshing(false);

                        // If user refreshes the servers list while already connected, update switch
                        // by asking the service to send active connection data
                        updateActiveConnection();
                    } catch (JSONException e)
                    {
                        e.printStackTrace();
                    }
                    mPendingHttpRequests--;
                }, error ->
                {
                    mPendingHttpRequests--;
                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), R.string.getserverserror, Toast.LENGTH_LONG).show());
                    binding.pullToRefresh.setRefreshing(false);
                }
        );

        // Add the request to the RequestQueue.
        queue.add(stringRequest);
        mPendingHttpRequests++;
        queue.addRequestEventListener((request, event) ->
        {
            if (mPendingHttpRequests == 0)
            {
                runOnUiThread(() -> binding.serversListView.invalidateViews());
                // Now that we have all the servers updated, apply user preferences
                if (bApplyUserSettings)
                {
                    applyUserSettings();
                }
            }
        });
    }

    private void updateActiveConnection()
    {
        // ask the service to send active connection data
        Intent intent = new Intent("JustVpnMsg");
        intent.putExtra("request", "getconnection");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        menu.findItem(R.id.action_settings).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener()
        {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem)
            {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
                return true;
            }
        });
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