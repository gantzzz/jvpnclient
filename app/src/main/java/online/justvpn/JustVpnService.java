package online.justvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
public class JustVpnService extends VpnService implements Handler.Callback {
    private static final String TAG = JustVpnService.class.getSimpleName();
    public static final String ACTION_CONNECT = "online.justvpn.START";
    public static final String ACTION_DISCONNECT = "online.justvpn.STOP";
    public static final String ACTION_GETCONNECTION = "online.justvpn.GETCONNECTION";
    private Handler mHandler;
    private Timer mConnectionCheckTimer = null;
    private long CONNECTION_CHECK_PERIOD =  TimeUnit.SECONDS.toMillis(10);
    private static class Connection extends Pair<Thread, ParcelFileDescriptor>
    {
        public Connection(Thread thread, ParcelFileDescriptor pfd)
        {
            super(thread, pfd);
        }
    }
    private final AtomicReference<Thread> mConnectingThread = new AtomicReference<>();
    private final AtomicReference<Connection> mConnection = new AtomicReference<>();
    private AtomicInteger mNextConnectionId = new AtomicInteger(1);
    private JustVpnConnection mJustVpnConnection;
    private String mServer;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            // Get extra data included in the Intent
            String message = intent.getStringExtra("request");
            if (message != null && message.equals("getconnection"))
            {
                if (mJustVpnConnection != null)
                {
                    sendMessageToActivity("connected:" + mJustVpnConnection.getServerIp());
                }
            }
        }
    };

    @Override
    public void onCreate()
    {
        // The handler is only used to show messages.
        if (mHandler == null)
        {
            mHandler = new Handler(this);
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter("JustVpnMsg"));
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
                    if (mJustVpnConnection.mLastPacketReceived + 5000 <= currentTime)
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
        if (intent != null)
        {
            mServer = intent.getStringExtra("ip");
        }

        switch (intent.getAction())
        {
            case ACTION_DISCONNECT:
                disconnect();
                return START_NOT_STICKY;


            case ACTION_CONNECT:
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

        startConnection(new JustVpnConnection(this, mNextConnectionId.getAndIncrement(), mServer, 8811));
    }
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
    private void disconnect()
    {
        if (mConnectionCheckTimer != null)
        {
            mConnectionCheckTimer.cancel();
            mConnectionCheckTimer = null;
        }

        if (mJustVpnConnection != null)
        {
            mJustVpnConnection.Disconnect();
        }
        mHandler.sendEmptyMessage(R.string.disconnected);
        setConnectingThread(null);
        setConnection(null);
        stopForeground(true);
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

        startForeground(1, new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.vpn_icon)
                .setContentText(getString(message))
                .build());
    }

    private void sendMessageToActivity(String msg)
    {
        Intent intent = new Intent("JustVpnMsg");
        intent.putExtra("Status", msg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}