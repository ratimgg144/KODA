package ge.koda.bmw.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only ENET transport routing. Supports ordinary Ethernet Networks and tethered eth* interfaces. */
public final class AndroidEnetNetwork {
    private AndroidEnetNetwork() {}

    public static final class Route implements EnetClient.Link {
        // null when a tethered USB Ethernet interface is not published as Android ConnectivityManager.Network.
        public final Network network;
        public final Inet4Address localIp;
        public final int prefix;
        public final String kind;

        private Route(Network network, Inet4Address localIp, int prefix, String kind) {
            this.network = network;
            this.localIp = localIp;
            this.prefix = prefix;
            this.kind = kind;
        }

        @Override public DatagramSocket openUdp() throws IOException {
            DatagramSocket socket = new DatagramSocket(null);
            try {
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                // Binding explicitly to eth*/USB address also works for tethered downstream interfaces
                // that do not appear in ConnectivityManager.getAllNetworks().
                socket.bind(new InetSocketAddress(localIp, 0));
                if (network != null) network.bindSocket(socket);
                return socket;
            } catch (IOException | RuntimeException ex) {
                socket.close();
                throw ex;
            }
        }

        @Override public Socket openTcp(String host, int port) throws IOException {
            Socket socket = new Socket();
            try {
                if (network != null) network.bindSocket(socket);
                else socket.bind(new InetSocketAddress(localIp, 0));
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 4500);
                return socket;
            } catch (IOException | RuntimeException ex) {
                try { socket.close(); } catch (IOException ignore) {}
                throw ex;
            }
        }

        @Override public List<InetAddress> discoveryDestinations(String manualIp) throws IOException {
            if (manualIp != null && !manualIp.trim().isEmpty()) {
                String ip = manualIp.trim();
                if (!ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}$"))
                    throw new IOException("შეიყვანეთ სწორი IPv4 მისამართი");
                InetAddress addr = InetAddress.getByName(ip);
                if (!(addr instanceof Inet4Address) || addr.isAnyLocalAddress() || addr.isMulticastAddress())
                    throw new IOException("IPv4 მისამართი მიუღებელია");
                return Collections.singletonList(addr);
            }
            Set<InetAddress> dest = new LinkedHashSet<>();
            // Limited broadcast is important for ENET devices reached through Android
            // Ethernet tethering: the phone's local subnet may differ from the car's
            // network while the physical Ethernet link is still reachable.
            dest.add(InetAddress.getByName("255.255.255.255"));
            dest.add(broadcast(localIp, prefix));
            if (localIp.isLinkLocalAddress()) dest.add(InetAddress.getByName("169.254.255.255"));
            return new ArrayList<>(dest);
        }

        @Override public String description() {
            return kind + " · " + localIp.getHostAddress() + "/" + prefix;
        }
    }

    /**
     * wiredMode=true deliberately NEVER falls back to home Wi-Fi. This is necessary because
     * Ethernet tethering may expose eth0 with an IP but no ConnectivityManager Network.
     * Wi-Fi ENET remains selectable separately in Advanced settings.
     */
    public static List<Route> routes(Context context, boolean wiredMode) {
        Map<String, Route> selected = new LinkedHashMap<>();
        ConnectivityManager mgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (mgr != null) {
            for (Network network : mgr.getAllNetworks()) {
                NetworkCapabilities caps = mgr.getNetworkCapabilities(network);
                LinkProperties props = mgr.getLinkProperties(network);
                if (caps == null || props == null) continue;
                boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                if (wiredMode && !ethernet || !wiredMode && !wifi) continue;
                for (LinkAddress address : props.getLinkAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address ip = (Inet4Address) address.getAddress();
                    if (ip.isLoopbackAddress() || ip.isAnyLocalAddress()) continue;
                    String kind = ethernet ? "USB Ethernet (Android)" : "ENET Wi-Fi (Android)";
                    selected.put(ip.getHostAddress(), new Route(network, ip, address.getPrefixLength(), kind));
                }
            }
        }

        // Android's downstream Ethernet tethering does not always publish a Network object.
        // Find interfaces such as eth0 via java.net and route sockets by binding to that local IPv4.
        if (wiredMode) {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces != null && interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (!looksWired(ni.getName()) || !ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (!(ia.getAddress() instanceof Inet4Address)) continue;
                        Inet4Address ip = (Inet4Address) ia.getAddress();
                        if (ip.isLoopbackAddress() || ip.isAnyLocalAddress()) continue;
                        String key = ip.getHostAddress();
                        if (!selected.containsKey(key)) {
                            selected.put(key, new Route(null, ip, ia.getNetworkPrefixLength(),
                                    "USB Ethernet (" + ni.getName() + ")"));
                        }
                    }
                }
            } catch (SocketException | SecurityException ignored) {
                // Display a useful diagnostic below instead of crashing.
            }
        }
        return new ArrayList<>(selected.values());
    }

    private static boolean looksWired(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("eth") || n.startsWith("enx") || n.matches("en\\d+.*")
                || n.startsWith("usb") || n.startsWith("rndis") || n.startsWith("lan");
    }

    /** Diagnostic inventory to distinguish a missing adapter from an Ethernet adapter without IPv4. */
    public static String diagnosticReport(Context context, boolean wiredMode) {
        StringBuilder out = new StringBuilder("კავშირის რეჟიმი: ");
        out.append(wiredMode ? "USB-C ENET" : "ENET Wi-Fi").append('\n');
        boolean anyWired = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!looksWired(ni.getName())) continue;
                if (!ni.isUp()) continue;
                anyWired = true;
                StringBuilder address = new StringBuilder();
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        if (address.length() > 0) address.append(", ");
                        address.append(ia.getAddress().getHostAddress()).append('/')
                               .append(ia.getNetworkPrefixLength());
                    }
                }
                out.append("Ethernet ").append(ni.getName()).append(": ")
                   .append(address.length() == 0 ? "IPv4 არ არის" : address).append('\n');
            }
        } catch (SocketException | SecurityException ex) {
            out.append("Ethernet ინტერფეისის შემოწმება ვერ მოხერხდა: ")
               .append(ex.getClass().getSimpleName()).append('\n');
        }
        if (!anyWired) out.append("აქტიური Ethernet ინტერფეისი ვერ ვიპოვეთ\n");
        for (Route route : routes(context, wiredMode)) out.append("ხელმისაწვდომი გზა: ").append(route.description()).append('\n');
        return out.toString().trim();
    }

    public static String missingNetworkHint(Context context, boolean wiredMode) {
        if (!wiredMode) return "ENET Wi-Fi არ მოიძებნა. დაუკავშირდით შესაბამის ადაპტერის Wi-Fi ქსელს.";
        boolean physical = false;
        try {
            Enumeration<NetworkInterface> ni = NetworkInterface.getNetworkInterfaces();
            while (ni != null && ni.hasMoreElements()) {
                NetworkInterface iface = ni.nextElement();
                if (looksWired(iface.getName()) && iface.isUp()) physical = true;
            }
        } catch (SocketException | SecurityException ignored) {}
        if (physical) return "USB Ethernet ჩანს, მაგრამ მას IPv4 მისამართი არ აქვს. Android 11+-ზე ჩართეთ Ethernet tethering, ან Ethernet-ის სტატიკური IP, თუ ტელეფონი იძლევა ამის საშუალებას.";
        return "USB Ethernet არ ჩანს. შეამოწმეთ USB-C LAN ადაპტერი, OTG და Ethernet tethering. Wi-Fi ქსელი ამ რეჟიმში არ გამოიყენება.";
    }

    private static InetAddress broadcast(Inet4Address ip, int prefix) throws IOException {
        if (prefix < 0 || prefix > 32) throw new IOException("Invalid prefix");
        byte[] raw = ip.getAddress();
        long value = ((long) (raw[0] & 0xff) << 24) | ((long) (raw[1] & 0xff) << 16) |
                ((long) (raw[2] & 0xff) << 8) | ((long) (raw[3] & 0xff));
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long bcast = (value & mask) | (~mask & 0xFFFFFFFFL);
        return InetAddress.getByAddress(new byte[]{(byte)(bcast >>> 24), (byte)(bcast >>> 16),
                (byte)(bcast >>> 8), (byte)bcast});
    }
}
