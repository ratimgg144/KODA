package ge.koda.bmw.net;

import ge.koda.bmw.protocol.EnetProtocol;
import ge.koda.bmw.protocol.EnetProtocol.Packet;
import ge.koda.bmw.protocol.EnetProtocol.Vehicle;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real network I/O for vehicle discovery and strictly read-only VIN verification. */
public final class EnetClient {
    public interface Link {
        DatagramSocket openUdp() throws IOException;
        Socket openTcp(String ip, int port) throws IOException;
        List<InetAddress> discoveryDestinations(String manualIp) throws IOException;
        String description();
    }
    public interface Log { void line(String entry); }

    private final Link link;
    private final Log log;
    private static final int TESTER = 0x0E80;
    public EnetClient(Link link, Log log) { this.link = link; this.log = log; }

    /** Reuses the verified Android network route for read-only ECU scanning. */
    public EcuScanner scanner() { return new EcuScanner(link, log); }

    public Vehicle discover(String manualIp) throws IOException {
        log.line("ქსელი: " + link.description());
        List<InetAddress> destinations = link.discoveryDestinations(manualIp);
        if (destinations.isEmpty()) throw new IOException("საძიებო IP მისამართი მიუწვდომელია");
        for (InetAddress destination : destinations) {
            log.line("ძიება: " + destination.getHostAddress() + " (UDP 13400/6811)");
        }
        List<Vehicle> found = new ArrayList<>();
        try (DatagramSocket doip = link.openUdp(); DatagramSocket hsfz = link.openUdp()) {
            doip.setBroadcast(true);
            hsfz.setBroadcast(true);
            doip.setSoTimeout(160);
            hsfz.setSoTimeout(160);
            for (InetAddress target : destinations) {
                send(doip, target, 13400, EnetProtocol.doipDiscovery(2));
                send(doip, target, 13400, EnetProtocol.doipDiscovery(3));
                send(hsfz, target, 6811, EnetProtocol.hsfzDiscovery());
            }
            log.line("DoIP და HSFZ აღმოჩენის მოთხოვნები გაიგზავნა");
            long deadline = System.nanoTime() + 2_800_000_000L;
            while (System.nanoTime() < deadline) {
                Vehicle a = receive(doip, true);
                if (a != null) {
                    log.line("DoIP gateway: " + a.address);
                    found.add(a);
                    return a; // Preferred on a compatible G-series gateway.
                }
                Vehicle b = receive(hsfz, false);
                if (b != null && found.isEmpty()) {
                    log.line("HSFZ gateway: " + b.address);
                    found.add(b);
                    // Wait briefly for DoIP so a newer G-series gateway can take priority.
                    deadline = Math.min(deadline, System.nanoTime() + 500_000_000L);
                }
            }
        }
        if (!found.isEmpty()) return found.get(0);
        throw new IOException("მანქანის პასუხი ვერ მივიღეთ. შეამოწმეთ ENET ადაპტერი, IP მისამართი და ანთება (ACC/ON).");
    }

    private void send(DatagramSocket socket, InetAddress host, int port, byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, host, port));
        } catch (IOException e) {
            log.line("UDP გაგზავნის პრობლემა: " + e.getMessage());
        }
    }

    private Vehicle receive(DatagramSocket socket, boolean doip) throws IOException {
        byte[] buffer = new byte[2048];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(response);
            byte[] bytes = Arrays.copyOf(response.getData(), response.getLength());
            String ip = response.getAddress().getHostAddress();
            return doip ? EnetProtocol.parseDoipDiscovery(bytes, ip)
                    : EnetProtocol.parseHsfzDiscovery(bytes, ip);
        } catch (SocketTimeoutException ignore) {
            return null;
        }
    }

    /** Verifies gateway reachability and sends UDS 22 F190; no session changes or writes. */
    public String verifyVin(Vehicle vehicle) throws IOException {
        if ("DoIP".equals(vehicle.protocol)) return verifyDoip(vehicle);
        if ("HSFZ".equals(vehicle.protocol)) return verifyHsfz(vehicle);
        throw new IOException("უცნობი პროტოკოლი");
    }

    private String verifyDoip(Vehicle v) throws IOException {
        log.line("TCP კავშირი " + v.address + ":13400");
        try (Socket socket = link.openTcp(v.address, 13400)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(EnetProtocol.routingActivation(v.version, TESTER));
            socket.getOutputStream().flush();
            boolean activated = false;
            for (int i = 0; i < 4; i++) {
                Packet p = EnetProtocol.readDoip(socket.getInputStream());
                if (p.type == 0x0006 && p.payload.length >= 5) {
                    if (EnetProtocol.unsignedShort(p.payload, 0) != TESTER)
                        throw new IOException("Routing activation tester mismatch");
                    int code = p.payload[4] & 0xFF;
                    if (code != 0x10) throw new IOException("Routing activation rejected: 0x" + Integer.toHexString(code));
                    activated = true;
                    break;
                }
                if (p.type == 0x0000) throw new IOException("DoIP gateway rejected header");
            }
            if (!activated) throw new IOException("Routing activation response not received");
            log.line("DoIP routing activation მიღებულია");
            socket.getOutputStream().write(EnetProtocol.doipReadVin(v.version, TESTER, v.gatewayAddress));
            socket.getOutputStream().flush();
            for (int i = 0; i < 12; i++) {
                Packet p = EnetProtocol.readDoip(socket.getInputStream());
                if (p.type == 0x8003) throw new IOException("Gateway უარყოფს დიაგნოსტიკურ მოთხოვნას");
                if (p.type == 0x8001 && p.payload.length >= 7) {
                    if (EnetProtocol.unsignedShort(p.payload, 2) != TESTER) continue;
                    byte[] uds = Arrays.copyOfRange(p.payload, 4, p.payload.length);
                    String vin = EnetProtocol.udsVin(uds);
                    if (vin != null) return vin;
                    if (EnetProtocol.negativeResponse(uds))
                        throw new IOException("ECU არ უჭერს მხარს VIN-ის წაკითხვას: NRC 0x" + Integer.toHexString(uds[2] & 0xFF));
                }
            }
            throw new IOException("VIN UDS პასუხი არ დაბრუნდა");
        }
    }

    private String verifyHsfz(Vehicle v) throws IOException {
        log.line("TCP კავშირი " + v.address + ":6801");
        try (Socket socket = link.openTcp(v.address, 6801)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(EnetProtocol.hsfzReadVin(v.gatewayAddress));
            socket.getOutputStream().flush();
            for (int i = 0; i < 12; i++) {
                Packet p = EnetProtocol.readHsfz(socket.getInputStream());
                if (p.type == 0x0040 || p.type == 0x0041) throw new IOException("HSFZ gateway error 0x" + Integer.toHexString(p.type));
                if (p.type == 0x0001 && p.payload.length >= 5) {
                    if ((p.payload[1] & 0xFF) != 0xF4) continue;
                    byte[] uds = Arrays.copyOfRange(p.payload, 2, p.payload.length);
                    String vin = EnetProtocol.udsVin(uds);
                    if (vin != null) return vin;
                    if (EnetProtocol.negativeResponse(uds))
                        throw new IOException("ECU არ უჭერს მხარს VIN-ის წაკითხვას: NRC 0x" + Integer.toHexString(uds[2] & 0xFF));
                }
            }
            throw new IOException("VIN UDS პასუხი არ დაბრუნდა");
        }
    }
}
