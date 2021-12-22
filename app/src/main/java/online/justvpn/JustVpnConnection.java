package online.justvpn;

import static java.nio.charset.StandardCharsets.US_ASCII;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
public class JustVpnConnection implements Runnable {
    /**
     * Callback interface to let the {@link JustVpnService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor tunInterface);
    }
    /** Maximum packet size is constrained by the MTU, which is given as a signed short. */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;
    /** Time to wait in between losing the connection and retrying. */
    private static final long RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3);
    /** Time between keepalives if there is no traffic at the moment.
     *
     * TODO: don't do this; it's much better to let the connection die and then reconnect when
     *       necessary instead of keeping the network hardware up for hours on end in between.
     **/
    private static final long KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    /** Time to wait without receiving any response before assuming the server is gone. */
    private static final long RECEIVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);
    /**
     * Time between polling the VPN interface for new traffic, since it's non-blocking.
     *
     * TODO: really don't do this; a blocking read on another thread is much cleaner.
     */
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);
    /**
     * Number of periods of length {@IDLE_INTERVAL_MS} to wait before declaring the handshake a
     * complete and abject failure.
     *
     * TODO: use a higher-level protocol; hand-rolling is a fun but pointless exercise.
     */
    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;
    private final VpnService mService;
    private final int mConnectionId;
    private final String mServerName;
    private final int mServerPort;
    private final byte[] mSharedSecret;
    private PendingIntent mConfigureIntent;
    private OnEstablishListener mOnEstablishListener;

    private DatagramChannel mTunnel;

    public JustVpnConnection(final VpnService service, final int connectionId,
                            final String serverName, final int serverPort, final byte[] sharedSecret) {
        mService = service;
        mConnectionId = connectionId;
        mServerName = serverName;
        mServerPort= serverPort;
        mSharedSecret = sharedSecret;
    }
    /**
     * Optionally, set an intent to configure the VPN. This is {@code null} by default.
     */
    public void setConfigureIntent(PendingIntent intent) {
        mConfigureIntent = intent;
    }
    public void setOnEstablishListener(OnEstablishListener listener) {
        mOnEstablishListener = listener;
    }
    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void run()
    {
        try {
            Log.i(getTag(), "Starting");
            // If anything needs to be obtained using the network, get it now.
            // This greatly reduces the complexity of seamless handover, which
            // tries to recreate the tunnel without shutting down everything.
            // In this demo, all we need to know is the server address.
            final SocketAddress serverAddress = new InetSocketAddress(mServerName, mServerPort);
            // We try to create the tunnel several times.
            // TODO: The better way is to work with ConnectivityManager, trying only when the
            // network is available.
            // Here we just use a counter to keep things simple.
            for (int attempt = 0; attempt < 10; ++attempt) {
                // Reset the counter if we were connected.
                if (run(serverAddress)) {
                    attempt = 0;
                }
                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000);
            }
            Log.i(getTag(), "Giving up");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            Log.e(getTag(), "Connection failed, exiting", e);
        }
    }
    public void ProcessControl(ByteBuffer packet, int length) throws IOException {
        String s = new String(packet.array(), StandardCharsets.UTF_8);
        if (s.contains("action:keepalive"))
        {
            // response to the server with keepalive
            ByteBuffer p = ByteBuffer.allocate(28);
            // Control messages always start with zero.
            String action = "action:keepalive";
            p.put((byte) 0).put(action.getBytes()).flip();
            // Send the secret several times in case of packet loss.
            for (int i = 0; i < 3; ++i)
            {
                p.position(0);
                mTunnel.write(p);
            }
            p.clear();
        }
    }
    public void Disconnect()
    {

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try  {
                    // send disconnect control message to server
                    ByteBuffer packet = ByteBuffer.allocate(1024);
                    // Control messages always start with zero.
                    String action = "action:disconnect";
                    packet.put((byte) 0).put(action.getBytes()).flip();
                    // Send the secret several times in case of packet loss.
                    for (int i = 0; i < 3; ++i)
                    {
                        packet.position(0);
                        mTunnel.write(packet);
                    }
                    packet.clear();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private boolean run(SocketAddress server)
            throws IOException, InterruptedException, IllegalArgumentException
    {
        ParcelFileDescriptor iface = null;
        boolean connected = false;
        // Create a DatagramChannel as the VPN tunnel.
        try
        {
            mTunnel = DatagramChannel.open();
            // Protect the tunnel before connecting to avoid loopback.
            if (!mService.protect(mTunnel.socket()))
            {
                throw new IllegalStateException("Cannot protect the tunnel");
            }
            // Connect to the server.
            mTunnel.connect(server);
            // For simplicity, we use the same thread for both reading and
            // writing. Here we put the tunnel into non-blocking mode.
            mTunnel.configureBlocking(false);
            // Authenticate and configure the virtual network interface.
            iface = handshake();
            // Now we are connected. Set the flag.
            connected = true;
            // Packets to be sent are queued in this input stream.
            FileInputStream in = new FileInputStream(iface.getFileDescriptor());
            // Packets received need to be written to this output stream.
            FileOutputStream out = new FileOutputStream(iface.getFileDescriptor());
            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);
            // Timeouts:
            //   - when data has not been sent in a while, send empty keepalive messages.
            //   - when data has not been received in a while, assume the connection is broken.
            long lastSendTime = System.currentTimeMillis();
            long lastReceiveTime = System.currentTimeMillis();
            // We keep forwarding packets till something goes wrong.
            while (true)
            {
                // Read the outgoing packet from the input stream.
                int length = in.read(packet.array());
                if (length > 0) {
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length);
                    mTunnel.write(packet);
                    packet.clear();
                }
                // Read the incoming packet from the tunnel.
                length = mTunnel.read(packet);
                if (length > 0)
                {
                    // Ignore control messages, which start with zero.
                    if (packet.get(0) != 0)
                    {
                        // Write the incoming packet to the output stream.
                        out.write(packet.array(), 0, length);
                    }
                    else
                    {
                        ProcessControl(packet, length);
                    }
                    packet.clear();
                }
            }
        } catch (SocketException e) {
            Log.e(getTag(), "Cannot use socket", e);
        } finally {
            if (iface != null) {
                try {
                    iface.close();
                } catch (IOException e) {
                    Log.e(getTag(), "Unable to close interface", e);
                }
            }
        }
        return connected;
    }
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private ParcelFileDescriptor handshake()
            throws IOException, InterruptedException {
        // To build a secured tunnel, we should perform mutual authentication
        // and exchange session keys for encryption. To keep things simple in
        // this demo, we just send the shared secret in plaintext and wait
        // for the server to send the parameters.
        // Allocate the buffer for handshaking. We have a hardcoded maximum
        // handshake size of 1024 bytes, which should be enough for demo
        // purposes.
        // send disconnect control message to server
        ByteBuffer packet = ByteBuffer.allocate(1500);

        // Control messages always start with zero.
        String action = "action:connect";
        packet.put((byte) 0).put(action.getBytes()).flip();
        packet.position(0);
        mTunnel.write(packet);
        packet.clear();

        // request parameters
        action ="action:getparameters";
        packet.put((byte) 0).put(action.getBytes()).flip();
        packet.position(0);
        mTunnel.write(packet);
        packet.clear();

        // Wait for the parameters within a limited time.
        for (int i = 0; i < MAX_HANDSHAKE_ATTEMPTS; ++i)
        {
            Thread.sleep(IDLE_INTERVAL_MS);
            // Normally we should not receive random packets. Check that the first
            // byte is 0 as expected.
            int length = mTunnel.read(packet);
            if (length > 0 && packet.get(0) == 0)
            {
                ParcelFileDescriptor descriptor = configure(new String(packet.array(), 1, length - 1, US_ASCII).trim());
                // signal configured

                packet.position(0);
                packet.clear();
                action ="action:configured";
                packet.put((byte) 0).put(action.getBytes()).flip();
                packet.position(0);
                mTunnel.write(packet);
                packet.position(0);
                packet.clear();

                return descriptor;
            }
        }
        throw new IOException("Timed out");
    }
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private ParcelFileDescriptor configure(String parameters) throws IllegalArgumentException
    {
        // Configure a builder while parsing the parameters.
        VpnService.Builder builder = mService.new Builder();
        for (String parameter : parameters.split(";")) {
            String[] fields = parameter.split(":");
            try {
                if (fields[0].equals("mtu"))
                {
                    builder.setMtu(Short.parseShort(fields[1]));
                }
                else if (fields[0].equals("address"))
                {
                    builder.addAddress(fields[1], Integer.parseInt(fields[2]));
                }
                else if (fields[0].equals("route"))
                {
                    builder.addRoute(fields[1], Integer.parseInt(fields[2]));
                }
                else if (fields[0].equals("dns"))
                {
                    builder.addDnsServer(fields[1]);
                }

            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad parameter: " + parameter);
            }
        }
        // Create a new interface using the builder and save the parameters.
        final ParcelFileDescriptor vpnInterface;

        builder.setSession(mServerName).setConfigureIntent(mConfigureIntent);

        synchronized (mService) {
            vpnInterface = builder.establish();
            if (mOnEstablishListener != null) {
                mOnEstablishListener.onEstablish(vpnInterface);
            }
        }
        Log.i(getTag(), "New interface: " + vpnInterface + " (" + parameters + ")");
        return vpnInterface;
    }

    private final String getTag() {
        return JustVpnConnection.class.getSimpleName() + "[" + mConnectionId + "]";
    }
}