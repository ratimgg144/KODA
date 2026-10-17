import ge.koda.bmw.net.EnetClient;
import ge.koda.bmw.protocol.EnetProtocol;
import ge.koda.bmw.protocol.EnetProtocol.Vehicle;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class EnetProtocolTests {
    private static final String VIN = "WBA12345678901234";
    private static int passed = 0;
    private static void ok(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        System.out.println("PASS  " + name);
    }
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] combine(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) len += a.length;
        byte[] result = new byte[len];
        int n = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, result, n, a.length); n += a.length; }
        return result;
    }
    private static byte[] discoveryPayload() {
        return combine(ascii(VIN), new byte[]{0x38, 0x28}, new byte[]{1,2,3,4,5,6},
                new byte[]{0,1,2,3,4,5}, new byte[]{0});
    }
    private static void testPackets() {
        ok(VIN.length() == 17, "Fixture VIN length");
        byte[] ann = EnetProtocol.doip(2, 4, discoveryPayload());
        Vehicle a = EnetProtocol.parseDoipDiscovery(ann, "169.254.1.10");
        ok(a != null && a.protocol.equals("DoIP") && a.gatewayAddress == 0x3828 && a.vin.equals(VIN), "DoIP vehicle identification");
        ok(EnetProtocol.parseDoipDiscovery(EnetProtocol.doip(3,4,discoveryPayload()),"x") != null, "DoIP version 3 discovery");
        byte[] bad = ann.clone(); bad[1] = 1;
        ok(EnetProtocol.parseDoipDiscovery(bad,"x") == null, "Invalid inverse version rejected");
        bad = ann.clone(); bad[7] = (byte) 0xff;
        ok(EnetProtocol.parseDoipDiscovery(bad,"x") == null, "Invalid declared length rejected");
        bad = ann.clone(); bad[10] = (byte)'I'; // VIN excludes I
        ok(EnetProtocol.parseDoipDiscovery(bad,"x") == null, "Invalid VIN rejected");
        byte[] hsfz = EnetProtocol.hsfz(0x11, ascii("DIAGADR10BMWMACAABBCCDDEEFFBMWVIN" + VIN));
        Vehicle b = EnetProtocol.parseHsfzDiscovery(hsfz,"169.254.1.2");
        ok(b != null && b.vin.equals(VIN) && b.gatewayAddress == 0x10 && b.mac.equals("AABBCCDDEEFF"), "HSFZ identification markers");
        ok(EnetProtocol.parseHsfzDiscovery(EnetProtocol.hsfz(0x2,ascii("BMWVIN"+VIN)),"x") == null, "HSFZ ACK not accepted as identity");
        ok(Arrays.equals(EnetProtocol.hsfzReadVin(0x10),new byte[]{0,0,0,5,0,1,(byte)0xF4,0x10,0x22,(byte)0xF1,(byte)0x90}), "HSFZ VIN request wire bytes");
        ok(EnetProtocol.doipReadVin(2,0x0E80,0x3828).length == 15, "DoIP VIN request frame size");
        ok(EnetProtocol.routingActivation(2,0x0E80).length == 15, "DoIP routing activation size");
        ok(VIN.equals(EnetProtocol.udsVin(combine(new byte[]{0x62,(byte)0xF1,(byte)0x90},ascii(VIN)))), "Read-only UDS VIN decode");
        ok(EnetProtocol.udsVin(new byte[]{0x7F,0x22,0x31}) == null, "Negative UDS response not VIN");
        ok(EnetProtocol.negativeResponse(new byte[]{0x7F,0x22,0x31}), "UDS NRC error detected");
        ok(!EnetProtocol.negativeResponse(new byte[]{0x7F,0x22,0x78}), "UDS pending response distinguished");
    }

    private static void testFakeDoipCar() throws Exception {
        try (DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1",13400));
             ServerSocket server = new ServerSocket(13400, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            CompletableFuture<Void> udpTask = CompletableFuture.runAsync(() -> {
                try {
                    udp.setSoTimeout(4000);
                    byte[] buf = new byte[128];
                    DatagramPacket request = new DatagramPacket(buf,buf.length);
                    udp.receive(request);
                    byte[] response = EnetProtocol.doip(2, 4, discoveryPayload());
                    udp.send(new DatagramPacket(response,response.length,request.getAddress(),request.getPort()));
                } catch (Throwable t) { serverError.set(t); }
            });
            CompletableFuture<Void> tcpTask = CompletableFuture.runAsync(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(4000);
                    EnetProtocol.Packet p = EnetProtocol.readDoip(client.getInputStream());
                    if (p.type != 5 || p.payload.length != 7) throw new AssertionError("routing activation expected");
                    client.getOutputStream().write(EnetProtocol.doip(2,6,new byte[]{0x0E,(byte)0x80,0x38,0x28,0x10,0,0,0,0}));
                    p = EnetProtocol.readDoip(client.getInputStream());
                    if (p.type != 0x8001 || p.payload.length != 7 || p.payload[4] != 0x22)
                        throw new AssertionError("UDS read VIN expected");
                    client.getOutputStream().write(EnetProtocol.doip(2,0x8002,new byte[]{0x38,0x28,0x0E,(byte)0x80,0}));
                    client.getOutputStream().write(EnetProtocol.doip(2,0x8001,combine(new byte[]{0x38,0x28,0x0E,(byte)0x80,0x62,(byte)0xF1,(byte)0x90},ascii(VIN))));
                } catch (Throwable t) { serverError.set(t); }
            });
            EnetClient.Link link = new EnetClient.Link() {
                @Override public DatagramSocket openUdp() throws java.io.IOException {
                    return new DatagramSocket(new InetSocketAddress("127.0.0.1",0));
                }
                @Override public Socket openTcp(String ip, int port) throws java.io.IOException {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(ip,port),1500);
                    return socket;
                }
                @Override public List<InetAddress> discoveryDestinations(String manualIp) throws java.io.IOException {
                    return List.of(InetAddress.getByName("127.0.0.1"));
                }
                @Override public String description() { return "Local simulator"; }
            };
            EnetClient client = new EnetClient(link, s -> {});
            Vehicle found = client.discover("127.0.0.1");
            ok(found != null && found.vin.equals(VIN) && found.protocol.equals("DoIP"), "Loopback fake gateway UDP discovery");
            String verified = client.verifyVin(found);
            ok(VIN.equals(verified), "Loopback fake gateway TCP routing activation + VIN");
            udpTask.get(5,TimeUnit.SECONDS);
            tcpTask.get(5,TimeUnit.SECONDS);
            if (serverError.get() != null) throw new AssertionError(serverError.get());
        }
    }
    private static void testFakeHsfzCar() throws Exception {
        try (DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1",6811));
             ServerSocket server = new ServerSocket(6801, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            CompletableFuture<Void> udpTask = CompletableFuture.runAsync(() -> {
                try {
                    udp.setSoTimeout(4000);
                    byte[] buf = new byte[128];
                    DatagramPacket request = new DatagramPacket(buf,buf.length);
                    udp.receive(request);
                    if (!Arrays.equals(Arrays.copyOf(request.getData(),request.getLength()),EnetProtocol.hsfzDiscovery()))
                        throw new AssertionError("HSFZ discovery bytes expected");
                    byte[] response = EnetProtocol.hsfz(0x11,ascii("DIAGADR10BMWMACAABBCCDDEEFFBMWVIN"+VIN));
                    udp.send(new DatagramPacket(response,response.length,request.getAddress(),request.getPort()));
                } catch (Throwable t) { serverError.set(t); }
            });
            CompletableFuture<Void> tcpTask = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(4000);
                    EnetProtocol.Packet frame = EnetProtocol.readHsfz(peer.getInputStream());
                    if (frame.type != 1 || frame.payload.length != 5 || (frame.payload[2] & 255) != 0x22)
                        throw new AssertionError("HSFZ UDS read expected");
                    peer.getOutputStream().write(EnetProtocol.hsfz(2, frame.payload));
                    peer.getOutputStream().write(EnetProtocol.hsfz(1,combine(new byte[]{0x10,(byte)0xF4,0x62,(byte)0xF1,(byte)0x90},ascii(VIN))));
                } catch (Throwable t) { serverError.set(t); }
            });
            EnetClient.Link link = new EnetClient.Link() {
                @Override public DatagramSocket openUdp() throws java.io.IOException { return new DatagramSocket(new InetSocketAddress("127.0.0.1",0)); }
                @Override public Socket openTcp(String ip,int port) throws java.io.IOException { return new Socket(ip,port); }
                @Override public List<InetAddress> discoveryDestinations(String manualIp) throws java.io.IOException { return List.of(InetAddress.getByName("127.0.0.1")); }
                @Override public String description() { return "HSFZ local simulator"; }
            };
            EnetClient client = new EnetClient(link, s -> {});
            Vehicle found = client.discover("127.0.0.1");
            ok(found != null && found.protocol.equals("HSFZ") && found.vin.equals(VIN), "Loopback fake HSFZ gateway UDP discovery");
            ok(VIN.equals(client.verifyVin(found)),"Loopback fake HSFZ gateway TCP VIN read");
            udpTask.get(5,TimeUnit.SECONDS);
            tcpTask.get(5,TimeUnit.SECONDS);
            if (serverError.get() != null) throw new AssertionError(serverError.get());
        }
    }
    public static void main(String[] args) throws Exception {
        testPackets();
        testFakeDoipCar();
        testFakeHsfzCar();
        System.out.println("\n"+passed+" checks passed. No physical BMW has been tested.");
    }
}
