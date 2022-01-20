package online.justvpn;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.DialogFragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.app.Activity;
import android.net.VpnService;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity implements SubscribeDialog.NoticeDialogListener
{
    private ActivityMainBinding binding;
    private boolean mUserVpnAllowed = false;
    public Activity mActivity;
    private boolean mBillingActive = false;
    private boolean mProUser = false;
    private BillingClient mBillingClient;

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
                mProUser = true;
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
        binding.pullToRefresh.setOnRefreshListener(this::getServersAvailable);

        binding.pullToRefresh.setRefreshing(true);
        getServersAvailable();

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
                    checkSubscription();
                }
            }
            @Override
            public void onBillingServiceDisconnected()
            {
                mBillingActive = false;
            }
        });
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
                        Toast.makeText(getApplicationContext(), "You need to be signed up with google account in order to user JustVPN", Toast.LENGTH_LONG).show();
                        mProUser = true; // TODO: TEMPORARELY ONLY !!!!!!!
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
                        runOnUiThread(() -> {
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
                    service.putExtra("ip", model.get_ip());
                    startForegroundService(service.setAction(JustVpnService.ACTION_CONNECT));
                }
            }
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
                            int id = Integer.valueOf(serverArr.get(0).toString());
                            String ip = serverArr.get(1).toString();
                            String country = serverArr.get(2).toString();
                            dataModels.add(new ServerListItemDataModel(id, ip, country));
                        }

                        binding.serversListView.setAdapter(new ServerListItemAdaptor(getApplicationContext(), dataModels));

                        // If user refreshes the servers list while already connected, update switch
                        // by asking the service to send active connection data
                        updateActiveConnection();
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

    private void updateActiveConnection()
    {
        // ask the service to send active connection data
        Intent intent = new Intent("JustVpnMsg");
        intent.putExtra("request", "getconnection");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
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