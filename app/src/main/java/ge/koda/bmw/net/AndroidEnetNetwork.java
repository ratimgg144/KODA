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
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Routes every connection over the selected Ethernet/Wi-Fi network, not mobile data. */
public final class AndroidEnetNetwork {
    private AndroidEnetNetwork() {}

    public static final class Route implements EnetClient.Link {
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
                socket.bind(new InetSocketAddress(localIp, 0));
                network.bindSocket(socket);
                return socket;
            } catch (IOException | RuntimeException ex) {
                socket.close();
                throw ex;
            }
        }
        @Override public Socket openTcp(String host, int port) throws IOException {
            Socket socket = new Socket();
            try {
                network.bindSocket(socket);
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
                return java.util.Collections.singletonList(addr);
            }
            Set<InetAddress> destinations = new LinkedHashSet<>();
            destinations.add(broadcast(localIp, prefix));
            if (localIp.isLinkLocalAddress()) {
                destinations.add(InetAddress.getByName("169.254.255.255"));
            }
            return new ArrayList<>(destinations);
        }
        @Override public String description() {
            return kind + " · " + localIp.getHostAddress() + "/" + prefix;
        }
    }

    public static List<Route> routes(Context context) {
        ConnectivityManager mgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        List<Route> out = new ArrayList<>();
        if (mgr == null) return out;
        for (Network network : mgr.getAllNetworks()) {
            NetworkCapabilities caps = mgr.getNetworkCapabilities(network);
            LinkProperties props = mgr.getLinkProperties(network);
            if (caps == null || props == null) continue;
            boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            if (!ethernet && !wifi) continue;
            for (LinkAddress linkAddr : props.getLinkAddresses()) {
                if (!(linkAddr.getAddress() instanceof Inet4Address)) continue;
                Inet4Address ip = (Inet4Address) linkAddr.getAddress();
                if (ip.isLoopbackAddress() || ip.isAnyLocalAddress()) continue;
                out.add(new Route(network, ip, linkAddr.getPrefixLength(), ethernet ? "USB Ethernet" : "Wi-Fi"));
            }
        }
        out.sort(Comparator.comparingInt(a -> a.kind.equals("USB Ethernet") ? 0 : 1));
        return out;
    }

    public static String missingNetworkHint(Context context) {
        ConnectivityManager mgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (mgr != null) for (Network n : mgr.getAllNetworks()) {
            NetworkCapabilities caps = mgr.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "Ethernet აღმოჩნდა, მაგრამ IPv4 მისამართი არ აქვს. Android-ზე ENET-ისთვის შესაძლოა 169.254.x.x/16 სტატიკური IP-ის დაყენება იყოს საჭირო.";
            }
        }
        return "USB-C Ethernet ადაპტერი შეაერთეთ ან დაუკავშირდით ENET Wi-Fi ადაპტერის ქსელს.";
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
