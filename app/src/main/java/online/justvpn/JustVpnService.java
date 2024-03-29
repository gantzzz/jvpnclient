package online.justvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class JustVpnService extends VpnService implements Handler.Callback
{
    private static final String TAG = JustVpnService.class.getSimpleName();
    public static final String ACTION_CONNECT = "online.justvpn.START";
    public static final String ACTION_DISCONNECT = "online.justvpn.STOP";
    private Handler mHandler;
    private Timer mConnectionCheckTimer = null;
    /*
    Server will send keepalive every 15 seconds per connection and will repeat 3 times before
    the connection will be dropped on the server side. So there is no need to check whether
    connection is stuck more often than once per 60 seconds (15 x 3 = 45 + some delta to give last chance)
     */
    private final long CONNECTION_CHECK_PERIOD =  TimeUnit.SECONDS.toMillis(60);

    private static class Connection extends Pair<Thread, ParcelFileDescriptor>
    {
        public Connection(Thread thread, ParcelFileDescriptor pfd)
        {
            super(thread, pfd);
        }
    }
    private final AtomicReference<Thread> mConnectingThread = new AtomicReference<>();
    private final AtomicReference<Connection> mConnection = new AtomicReference<>();
    private final AtomicInteger mNextConnectionId = new AtomicInteger(1);
    private JustVpnConnection mJustVpnConnection;
    private String mServer;
    private String mPurchaseToken;

    private class MyBroadcastReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            // Get extra data included in the Intent
            String message = intent.getStringExtra("request");
            String sAction = intent.getAction();
            if (sAction.equals("JustVpnMsg"))
            {
                if (message != null && message.equals("getconnection"))
                {
                    if (mJustVpnConnection != null)
                    {
                        sendMessageToActivity("connected:" + mJustVpnConnection.getServerIp());
                    }
                }
            }
        }
    }

    private final MyBroadcastReceiver mBroadcastReceiver = new MyBroadcastReceiver();

    @Override
    public void onCreate()
    {
        // The handler is only used to show messages.
        if (mHandler == null)
        {
            mHandler = new Handler(this);
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, new IntentFilter("JustVpnMsg"));
    }

    private void startConnectionMonitor()
    {
        if (mConnectionCheckTimer != null)
        {
            mConnectionCheckTimer.cancel();
        }
        mConnectionCheckTimer = new Timer();
        mConnectionCheckTimer.schedule(new TimerTask()
        {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void run()
            {
                long currentTime = System.currentTimeMillis();

                if (mJustVpnConnection != null)
                {
                    /* if delta time between the current time and last packet was received is more than
                    45 seconds (every 15 seconds keepalive is sent by the server for 3 times before
                    the server disconnects the client), reconnect the connection
                    */
                    if ((currentTime - mJustVpnConnection.mLastPacketReceived) > 45000)
                    {
                        // we haven't received anything for awhile, reconnect
                        setConnectingThread(null);
                        setConnection(null);
                        connect(true);
                    }
                }
            }
        }, CONNECTION_CHECK_PERIOD, CONNECTION_CHECK_PERIOD);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if (intent == null)
        {
            return START_STICKY;
        }

        switch (intent.getAction())
        {
            case ACTION_DISCONNECT:
                disconnect();
                return START_NOT_STICKY;


            case ACTION_CONNECT:
                // Don't overwrite mServer and mPurchaseToken if the intent
                // does not have this extras to support re-connection
                // from notification bar
                String server = intent.getStringExtra("ip");
                if (server != null)
                {
                    mServer = server;
                }
                String purchaseToken = intent.getStringExtra("subscriptionToken");
                if (purchaseToken != null)
                {
                    mPurchaseToken = purchaseToken;
                }

                // Timer is restarted once connection is established
                if (mConnectionCheckTimer != null)
                {
                    mConnectionCheckTimer.cancel();
                    mConnectionCheckTimer = null;
                }

                connect(false);
                return START_STICKY;

            default:
                return START_NOT_STICKY;
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onDestroy()
    {
        disconnect();
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public boolean handleMessage(Message message)
    {
        Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
        if (message.what != R.string.disconnected)
        {
            updateForegroundNotification(message.what);
        }
        return true;
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void connect(boolean bQuiet)
    {
        // Become a foreground service. Background services can be VPN services too, but they can
        // be killed by background check before getting a chance to receive onRevoke().
        if (!bQuiet)
        {
            updateForegroundNotification(R.string.connecting);
            mHandler.sendEmptyMessage(R.string.connecting);
        }

        startConnection(new JustVpnConnection(this, mNextConnectionId.getAndIncrement(), mServer, 8811, mPurchaseToken));
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startConnection(final JustVpnConnection connection)
    {
        mJustVpnConnection = connection;
        // Replace any existing connecting thread with the  new one.
        final Thread thread = new Thread(connection, "JustVpnThread");
        setConnectingThread(thread);

        connection.setOnConnectionStateListener((state, vpnInterface) ->
        {
            switch (state)
            {
                case ESTABLISHED:
                    mHandler.sendEmptyMessage(R.string.connected);
                    mConnectingThread.compareAndSet(thread, null);
                    setConnection(new Connection(thread, vpnInterface));
                    // once connected, monitor the connection health
                    startConnectionMonitor();

                    sendMessageToActivity("connected:" + mJustVpnConnection.getServerIp());
                    break;
                case NOSLOTS:
                    disconnect();
                    sendMessageToActivity("noslots");
                    break;

                case FAILED:
                case TIMEDOUT:
                    // don't disconnect a connection that was recently active
                    if (mConnectionCheckTimer == null)
                    {
                        disconnect();
                        sendMessageToActivity("failed");
                    }
                    break;
                case DISCONNECTED:
                    sendMessageToActivity("disconnected");
                    break;
                case NOTSUBSCRIBED:
                    sendMessageToActivity("notsubscribed");
                    break;

            }
        });

        thread.start();
    }
    private void setConnectingThread(final Thread thread)
    {
        final Thread oldThread = mConnectingThread.getAndSet(thread);
        if (oldThread != null)
        {
            oldThread.interrupt();
        }
    }
    private void setConnection(final Connection connection)
    {
        final Connection oldConnection = mConnection.getAndSet(connection);
        if (oldConnection != null)
        {
            try {
                oldConnection.first.interrupt();

                // Closing socket interface
                if (oldConnection.second != null)
                {
                    oldConnection.second.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Closing VPN interface", e);
            }
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void disconnect()
    {
        if (mConnectionCheckTimer != null)
        {
            mConnectionCheckTimer.cancel();
            mConnectionCheckTimer = null;
        }

        if (mJustVpnConnection != null)
        {
            mJustVpnConnection.Disconnect(JustVpnConnection.ConnectionState.DISCONNECTING);
        }

        mHandler.sendEmptyMessage(R.string.disconnected);
        setConnectingThread(null);
        setConnection(null);
        mJustVpnConnection = null;
        stopForeground(false);
        updateForegroundNotification(R.string.disconnected);
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void updateForegroundNotification(final int message)
    {
        final String NOTIFICATION_CHANNEL_ID = "JustVpn";
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);

        mNotificationManager.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT));

        // support connect/disconnect from the notification bar
        Intent actionIntent = new Intent(this, JustVpnService.class);
        String actionText;
        if (mJustVpnConnection != null && mJustVpnConnection.mConnected)
        {
            actionIntent.setAction(ACTION_DISCONNECT);
            actionText = "disconnect";
        }
        else
        {
            actionIntent.setAction(ACTION_CONNECT);
            actionText = "connect";
        }

        PendingIntent actionPendingIntent = PendingIntent.getService(this, 0, actionIntent, PendingIntent.FLAG_IMMUTABLE);

        startForeground(1, new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.vpn_icon)
                .setContentText(getString(message))
                .addAction(R.drawable.vpn_icon, actionText, actionPendingIntent)
                .build());
    }

    private void sendMessageToActivity(String msg)
    {
        Intent intent = new Intent("JustVpnMsg");
        intent.putExtra("Status", msg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}