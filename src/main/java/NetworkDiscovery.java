package negotiation.network;

import java.net.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * LAN auto-discovery using UDP broadcast.
 *
 * ─── How it works ────────────────────────────────────────────────────────────
 *
 *  HOST side (startHostListener):
 *    - Opens a UDP socket on DISCOVERY_PORT
 *    - Listens for QUERY packets from clients
 *    - When one arrives, replies with "CAR_NEG_HOST:<this-machine's-IP>"
 *
 *  CLIENT side (discoverHost):
 *    - Sends a QUERY packet to 255.255.255.255 (and all subnet broadcast addresses)
 *    - Waits up to TIMEOUT_MS for a reply
 *    - Returns the host's IP string, or null if nobody replied
 *
 * ─── Why not MAC address? ────────────────────────────────────────────────────
 *  MAC addresses only exist at Layer 2 (your switch).  Java can read your own
 *  MAC but cannot open a socket to someone else's MAC address — that requires
 *  knowing their IP anyway.  UDP broadcast is the standard LAN-discovery
 *  mechanism (used by Chromecast, Spotify Connect, printers, etc.).
 *
 * ─── Firewall note ───────────────────────────────────────────────────────────
 *  Windows may block incoming UDP on port 45678 by default.
 *  If auto-discover fails, allow UDP 45678 inbound on the host machine, OR
 *  fall back to typing the IP address manually in the launcher dialog.
 */
public class NetworkDiscovery {

    /** UDP port used exclusively for our discovery handshake. */
    public static final int    PORT       = 45678;
    private static final String QUERY     = "CAR_NEG_DISCOVER_V1";
    private static final String REPLY_PFX = "CAR_NEG_HOST:";

    /** How long the client waits for a host reply (ms). */
    public static final int TIMEOUT_MS = 3000;

    // ─────────────────────────────────────────────────────────────────────────
    // HOST side
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts a daemon thread that listens for discovery queries and responds.
     *
     * @param onClientFound optional callback — called with the client's IP each
     *                      time a new client broadcasts a discovery query
     * @return the listener thread (already started; daemon = exits with JVM)
     */
    public static Thread startHostListener(Consumer<String> onClientFound) {
        Thread t = new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket(PORT)) {
                sock.setBroadcast(true);
                System.out.println("[Discovery] Listening for peers on UDP port " + PORT);

                byte[] buf = new byte[256];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength()).trim();

                    if (QUERY.equals(msg)) {
                        // A client is looking for us — reply with our IP
                        String myIp   = getLocalIP();
                        byte[] reply  = (REPLY_PFX + myIp).getBytes();
                        sock.send(new DatagramPacket(
                                reply, reply.length,
                                pkt.getAddress(), pkt.getPort()));

                        System.out.println("[Discovery] Replied to client "
                                + pkt.getAddress().getHostAddress()
                                + " — told them host IP is " + myIp);

                        if (onClientFound != null) {
                            onClientFound.accept(pkt.getAddress().getHostAddress());
                        }
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.err.println("[Discovery] Listener error: " + e.getMessage());
                }
            }
        }, "DiscoveryListener");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT side
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Broadcasts a discovery query and returns the first host IP that replies.
     *
     * @return the host's IPv4 address string, or {@code null} if no host replied
     *         within {@link #TIMEOUT_MS} milliseconds
     */
    public static String discoverHost() {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(TIMEOUT_MS);
            byte[] query = QUERY.getBytes();

            // Broadcast to every available broadcast address on this machine
            // (255.255.255.255 + each interface's subnet broadcast, e.g. 192.168.1.255)
            List<String> broadcasts = getBroadcastAddresses();
            System.out.println("[Discovery] Broadcasting to: " + broadcasts);

            for (String addr : broadcasts) {
                try {
                    DatagramPacket pkt = new DatagramPacket(
                            query, query.length,
                            InetAddress.getByName(addr), PORT);
                    sock.send(pkt);
                } catch (Exception ignored) {
                    // Some interfaces may reject the send — keep trying others
                }
            }

            // Wait for the first reply
            byte[] buf  = new byte[256];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            sock.receive(resp); // throws SocketTimeoutException if no reply

            String msg = new String(resp.getData(), 0, resp.getLength()).trim();
            if (msg.startsWith(REPLY_PFX)) {
                String hostIp = msg.substring(REPLY_PFX.length());
                System.out.println("[Discovery] Host found at " + hostIp);
                return hostIp;
            }

        } catch (SocketTimeoutException e) {
            System.out.println("[Discovery] No host found within " + TIMEOUT_MS + "ms.");
        } catch (Exception e) {
            System.err.println("[Discovery] Error: " + e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns this machine's primary non-loopback IPv4 address.
     * Prefers WiFi/hotspot adapters over VirtualBox adapters.
     * Falls back to "127.0.0.1" if nothing else is found.
     */
    public static String getLocalIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String wifiIp = null;
            String fallbackIp = null;

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                String name = ni.getName().toLowerCase();
                boolean isWifi = name.contains("wi-fi") || name.contains("wlan") || name.contains("wireless");
                boolean isVirtualBox = name.contains("virtualbox") || name.contains("vbox");

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // Skip VirtualBox adapters
                        if (isVirtualBox) continue;
                        // Prefer WiFi/hotspot
                        if (isWifi) return ip;
                        // Store as fallback
                        if (fallbackIp == null) fallbackIp = ip;
                    }
                }
            }
            return fallbackIp != null ? fallbackIp : "127.0.0.1";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    /** Collects all broadcast addresses available on this machine. */
    private static List<String> getBroadcastAddresses() {
        List<String> result = new ArrayList<>();
        result.add("255.255.255.255"); // global broadcast — first attempt
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress bcast = ia.getBroadcast();
                    if (bcast != null && !result.contains(bcast.getHostAddress())) {
                        result.add(bcast.getHostAddress()); // e.g. 192.168.1.255
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }
}
