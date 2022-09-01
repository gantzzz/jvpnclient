package online.justvpn;

import static java.nio.charset.StandardCharsets.US_ASCII;
import android.app.PendingIntent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.TimeUnit;

public class JustVpnConnection implements Runnable
{
    private Thread mReceiverThread = null;

    public enum ConnectionState
    {
        INIT,
        CONNECTING,
        DISCONNECTING,
        ESTABLISHED,
        DISCONNECTED,
        TIMEDOUT,
        FAILED,
        NOSLOTS
    }
    /**
     * Callback interface to let the {@link JustVpnService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnConnectionStateListener
    {
        void onConnectionState(ConnectionState state, ParcelFileDescriptor vpnInterface);
    }
    /** Maximum packet size is constrained by the MTU, which is given as a signed short. */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(200);

    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;
    private final VpnService mService;
    private final int mConnectionId;
    private final String mServerAddress;
    private final int mServerPort;

    private OnConnectionStateListener mConnectionStateListener;

    private DatagramChannel mTunnel;

    public long mLastPacketReceived;
    public boolean mConnected = false;
    private String mSubscriptionToken;

    private ConnectionState mConnectionState;

    public String getServerIp()
    {
        return mServerAddress;
    }

    public JustVpnConnection(final VpnService service, final int connectionId,
                            final String serverName, final int serverPort, final String subscriptionToken)
    {
        mService = service;
        mConnectionId = connectionId;
        mServerAddress = serverName;
        mServerPort= serverPort;
        mConnectionState = ConnectionState.INIT;
        mSubscriptionToken = subscriptionToken;
    }

    public void setOnConnectionStateListener(OnConnectionStateListener listener)
    {
        mConnectionStateListener = listener;
    }

    private void notifyConnectionState()
    {
        if (mConnectionStateListener != null)
        {
            mConnectionStateListener.onConnectionState(mConnectionState, null);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void run()
    {
        try
        {
            Log.i(getTag(), "Starting");
            final SocketAddress serverAddress = new InetSocketAddress(mServerAddress, mServerPort);
            run(serverAddress);
        }
        catch (IOException | InterruptedException | IllegalArgumentException e)
        {
            Log.e(getTag(), "Cannot connect", e);
        }
        finally
        {
            notifyConnectionState();
        }
    }
    public void ProcessControl(ByteBuffer packet, int length) throws IOException
    {
        String s = new String(packet.array());
        if (s.contains("action:keepalive"))
        {
            // response to the server with keepalive
            ByteBuffer p = ByteBuffer.allocate(28);
            // Control messages always start with zero.
            String action = "action:keepalive";
            p.put((byte) 0).put(action.getBytes()).flip();
            p.position(0);
            mTunnel.write(p);
            p.clear();
        }
    }
    public void Disconnect()
    {
        mConnectionState = ConnectionState.DISCONNECTING;

        Thread thread = new Thread(() ->
        {
            try
            {
                // send disconnect control message to server
                ByteBuffer packet = ByteBuffer.allocate(1024);
                // Control messages always start with zero.
                String action = "action:disconnect";
                packet.put((byte) 0).put(action.getBytes()).flip();
                packet.position(0);
                mTunnel.write(packet);
                packet.clear();
                mConnectionState = ConnectionState.DISCONNECTED;
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });

        thread.start();
        try
        {
            thread.join();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        // wait read and write threads to finish
        if (mReceiverThread != null)
        {
            mReceiverThread.interrupt();
        }
        notifyConnectionState();
    }
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void run(SocketAddress server)
            throws IOException, InterruptedException, IllegalArgumentException
    {
        ParcelFileDescriptor iface = null;
        mConnected = false;
        // Create a DatagramChannel as the VPN tunnel.
        try
        {
            mConnectionState = ConnectionState.CONNECTING;
            mTunnel = DatagramChannel.open();
            // Protect the tunnel before connecting to avoid loopback.
            DatagramSocket socket = mTunnel.socket();
            if (!mService.protect(socket))
            {
                mConnectionState = ConnectionState.FAILED;
                throw new IllegalStateException("Cannot protect the tunnel");
            }
            // Connect to the server.
            mTunnel.connect(server);

            // For simplicity, we use the same thread for both reading and
            // writing. Here we put the tunnel into non-blocking mode.
            mTunnel.configureBlocking(false);

            // Authenticate and configure the virtual network interface.
            iface = handshake();
            mTunnel.configureBlocking(true);
            // Now we are connected. Set the flag.
            mConnected = true;
            // Packets to be sent are queued in this input stream.
            FileInputStream in = new FileInputStream(iface.getFileDescriptor());
            // Packets received need to be written to this output stream.
            FileOutputStream out = new FileOutputStream(iface.getFileDescriptor());
            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);
            // We keep forwarding packets till something goes wrong.

            mReceiverThread = new Thread(() ->
            {
                try
                {
                    ByteBuffer p = ByteBuffer.allocate(MAX_PACKET_SIZE);

                    while (mConnectionState != ConnectionState.DISCONNECTING)
                    {
                        // Read the incoming packet from the tunnel.
                        int len = mTunnel.read(p);
                        if (len > 0)
                        {
                            mLastPacketReceived = System.currentTimeMillis();
                            // Ignore control messages, which start with zero.
                            if (p.get(0) != 0)
                            {
                                // Write the incoming packet to the output stream.
                                out.write(p.array(), 0, len);
                            }
                            else
                            {
                                ProcessControl(p, len);
                            }
                            p.clear();
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            mReceiverThread.start();

            // To minimize busy looping, sleep thread if data remains 0 for a few cycles
            int nEmptyPacketCounter = 0;
            while (mConnectionState != ConnectionState.DISCONNECTING)
            {
                // Read the outgoing packet from the input stream.
                int length = in.read(packet.array());
                if (length > 0)
                {
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length);
                    mTunnel.write(packet);
                    packet.clear();
                    nEmptyPacketCounter = 0;
                }
                else
                {
                    nEmptyPacketCounter++;
                    if (nEmptyPacketCounter > 15)
                    {
                        Thread.sleep(TimeUnit.MILLISECONDS.toMillis(100));
                    }
                }
            }
        }
        catch (SocketException e)
        {
            Log.e(getTag(), "Cannot use socket", e);
        }
        catch (ClosedChannelException e)
        {
            Log.e(getTag(), "Connection closed", e);
        }
        finally
        {
            if (iface != null)
            {
                try
                {
                    iface.close();
                }
                catch (IOException e)
                {
                    Log.e(getTag(), "Unable to close interface", e);
                }
            }
            if (mConnectionState != ConnectionState.DISCONNECTING &&
                mConnectionState != ConnectionState.DISCONNECTED)
            {
                mConnectionState = ConnectionState.FAILED;
            }
        }
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

        // Wait for the parameters within a limited time.
        for (int i = 0; i < MAX_HANDSHAKE_ATTEMPTS; ++i)
        {
            // Control messages always start with zero.
            String action = "action:connect";
            packet.put((byte) 0).put(action.getBytes()).flip();
            packet.position(0);
            mTunnel.write(packet);
            packet.clear();

            int length = mTunnel.read(packet);
            if (length > 0 && packet.get(0) == 0)
            {
                // see if server accepts the connection
                String s = new String(packet.array());
                if (!s.contains("action:connected"))
                {
                    if (s.contains("reason:noslots"))
                    {
                        mConnectionState = ConnectionState.NOSLOTS;
                        throw new IOException("No slots");
                    }
                    continue;
                }
            }
            packet.clear();

            // request parameters
            action ="action:getparameters";
            packet.put((byte) 0).put(action.getBytes()).flip();
            packet.position(0);
            mTunnel.write(packet);
            packet.clear();

            // Normally we should not receive random packets. Check that the first
            // byte is 0 as expected.
            length = mTunnel.read(packet);
            if (length > 0 && packet.get(0) == 0)
            {
                ParcelFileDescriptor descriptor = configure(new String(packet.array(), 1, length - 1, US_ASCII).trim());

                // signal to server configured event
                packet.position(0);
                packet.clear();
                action ="action:configured";
                packet.put((byte) 0).put(action.getBytes()).flip();
                packet.position(0);
                mTunnel.write(packet);
                packet.position(0);
                packet.clear();

                // verify subscription on server side
                packet.position(0);
                packet.clear();
                action ="action:verifysubscription;token=" + mSubscriptionToken;
                packet.put((byte) 0).put(action.getBytes()).flip();
                packet.position(0);
                mTunnel.write(packet);
                packet.position(0);
                packet.clear();

                return descriptor;
            }
            else
            {
                Thread.sleep(IDLE_INTERVAL_MS); // next attempt
            }
        }
        mConnectionState = ConnectionState.TIMEDOUT;
        throw new IOException("Timed out");
    }
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private ParcelFileDescriptor configure(String parameters) throws IllegalArgumentException
    {
        // Configure a builder while parsing the parameters.
        VpnService.Builder builder = mService.new Builder();
        for (String parameter : parameters.split(";"))
        {
            if (!(parameters.contains("mtu") &&
                parameters.contains("address") &&
                parameters.contains("route") &&
                parameters.contains("dns")))
            {
                mConnectionState = ConnectionState.FAILED;
                throw new IllegalArgumentException("Bad parameters: " + parameters);
            }

            String[] fields = parameter.split(":");
            try
            {
                switch (fields[0]) {
                    case "mtu":
                        builder.setMtu(Short.parseShort(fields[1]));
                        break;
                    case "address":
                        builder.addAddress(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case "route":
                        builder.addRoute(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case "dns":
                        builder.addDnsServer(fields[1]);
                        break;
                }

            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad parameter: " + parameter);
            }
        }
        // Create a new interface using the builder and save the parameters.
        final ParcelFileDescriptor vpnInterface;

        synchronized (mService)
        {
            vpnInterface = builder.establish();
            mConnectionState = ConnectionState.ESTABLISHED;
            notifyConnectionState();
        }
        Log.i(getTag(), "New interface: " + vpnInterface + " (" + parameters + ")");
        return vpnInterface;
    }

    private String getTag()
    {
        return JustVpnConnection.class.getSimpleName() + "[" + mConnectionId + "]";
    }
}