package ge.koda.bmw.net;

import ge.koda.bmw.protocol.EnetProtocol;
import ge.koda.bmw.protocol.EnetProtocol.Packet;
import ge.koda.bmw.protocol.EnetProtocol.Vehicle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Bounded, READ ONLY diagnostic identification over an already-discovered BMW ENET link.
 * Only UDS 22 (ReadDataByIdentifier) and 19 (ReadDTCInformation) requests are emitted.
 * No coding writes, session switching, clears, routines or security access.
 * Addresses here are potential targets, NOT a vehicle's confirmed ECU inventory.
 */
public final class EcuScanner {
    public interface Progress { void onProgress(int done, int total, String message); }
    public static final class EcuInfo {
        public final int address;
        public final String label;
        public final String vin;
        public final String partNumber;
        public final boolean identified;
        public final int didNrc;
        public final List<String> faults = new ArrayList<>();
        public String faultStatus = "არ შემოწმებულა";
        public EcuInfo(int address, String label, String vin, String partNumber, boolean identified, int didNrc) {
            this.address = address; this.label = label; this.vin = vin;
            this.partNumber = partNumber; this.identified = identified; this.didNrc = didNrc;
        }
        public String addressText() { return String.format(Locale.ROOT, "0x%02X", address); }
    }
    private static final int TESTER = 0x0E80;
    private static final int[] CANDIDATES = {
        // Priority targets first. OEM wiring and ECU address mappings differ by generation.
        0x10, 0x12, 0x18, 0x29, 0x40, 0x60, 0x63, 0x78,
        0x01, 0x05, 0x0A, 0x0D, 0x13, 0x15, 0x19, 0x1A, 0x20, 0x22,
        0x24, 0x27, 0x28, 0x2A, 0x2C, 0x30, 0x32, 0x34, 0x35,
        0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3F, 0x41, 0x42, 0x43,
        0x50, 0x52, 0x54, 0x56, 0x58, 0x5A, 0x5C, 0x5E,
        0x61, 0x62, 0x64, 0x65, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D,
        0x70, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x79, 0x7A, 0x7B, 0x7C,
        0x80, 0x82, 0x83, 0x84, 0x85, 0x87, 0x88, 0x89, 0x8A,
        0x90, 0x92, 0x93, 0x94, 0x95, 0x97, 0x98
    };
    private final EnetClient.Link link;
    private final EnetClient.Log logger;
    private final int[] targets;
    public EcuScanner(EnetClient.Link link, EnetClient.Log logger) {
        this(link, logger, CANDIDATES);
    }
    /** Target overrides are for controlled testing, not automatic full-range sweeps. */
    public EcuScanner(EnetClient.Link link, EnetClient.Log logger, int[] targetAddresses) {
        if (targetAddresses == null || targetAddresses.length == 0 || targetAddresses.length > 220)
            throw new IllegalArgumentException("Invalid target list");
        for (int address : targetAddresses)
            if (address < 0 || address >= 0xF0 || address == 0xDF)
                throw new IllegalArgumentException("Functional or tester address forbidden");
        this.link = link; this.logger = logger; this.targets = targetAddresses.clone();
    }
    public static int numberOfTargets() { return CANDIDATES.length; }

    public List<EcuInfo> scan(Vehicle vehicle, Progress progress) throws IOException {
        if (vehicle == null) throw new IOException("ჯერ დაუკავშირდით BMW-ს");
        List<EcuInfo> found = new ArrayList<>();
        try (Session session = new Session(vehicle)) {
            // Keep the connection single-threaded. Do not flood the gateway.
            for (int i = 0; i < targets.length; i++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("სკანირება შეწყვეტილია");
                int address = targets[i];
                progress.onProgress(i, targets.length, "ECU " + hexByte(address));
                try {
                    byte[] response = session.request(address, new byte[]{0x22, (byte)0xF1, (byte)0x90}, 330);
                    boolean positive = isDidResponse(response, 0xF190);
                    // Negative UDS response from this exact ECU also proves diagnostic reachability,
                    // but does not prove identification support. Mark it explicitly.
                    boolean negative = isNegative(response, 0x22);
                    if (!positive && !negative) continue;
                    String vin = positive ? EnetProtocol.udsVin(response) : null;
                    int nrc = negative ? response[2] & 0xFF : -1;
                    String part = "";
                    if (positive) {
                        byte[] partResponse = session.request(address, new byte[]{0x22, (byte)0xF1, (byte)0x87}, 320);
                        if (isDidResponse(partResponse, 0xF187)) part = printable(partResponse, 3);
                    }
                    EcuInfo info = new EcuInfo(address, moduleHint(address), vin == null ? "" : vin,
                            part, positive, nrc);
                    found.add(info);
                    logger.line("ECU FOUND " + info.addressText() + " · " + info.label +
                        (positive ? " · 22 F190 OK" : " · negative response NRC " + hexByte(nrc)));
                } catch (SocketTimeoutException ignore) {
                    // This target didn't answer within the bounded time. It is NOT confirmed absent.
                } catch (IOException ex) {
                    logger.line("ECU " + hexByte(address) + ": " + ex.getMessage());
                    // A dead transport is not a negative ECU scan result. Stop and report it.
                    throw ex;
                }
                // Allows time for ECU gateway pacing while not waking other modules.
                try { Thread.sleep(25); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IOException("სკანირება შეწყვეტილია");
                }
            }
        }
        progress.onProgress(targets.length, targets.length,
                "სკანირება დასრულებულია · " + found.size() + " პასუხი");
        return found;
    }

    public void readFaultCodes(Vehicle vehicle, List<EcuInfo> modules, Progress progress) throws IOException {
        if (vehicle == null || modules == null || modules.isEmpty()) throw new IOException("ჯერ იპოვეთ ECU-ები");
        try (Session session = new Session(vehicle)) {
            for (int i = 0; i < modules.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("წაკითხვა შეწყვეტილია");
                EcuInfo ecu = modules.get(i);
                progress.onProgress(i, modules.size(), ecu.label + " · შეცდომები");
                ecu.faults.clear();
                byte[] reply = session.request(ecu.address, new byte[]{0x19, 0x02, (byte)0xFF}, 1000);
                if (reply == null) {
                    ecu.faultStatus = "ECU-მ არ უპასუხა";
                } else if (reply.length >= 3 && (reply[0] & 0xFF) == 0x59 && (reply[1] & 0xFF) == 0x02) {
                    // Reply: 59 02 statusAvailabilityMask [3-byte DTC, 1-byte status]*
                    int remainder = reply.length - 3;
                    if (remainder % 4 != 0) {
                        ecu.faultStatus = "უცნობი DTC პასუხის ფორმატი";
                    } else {
                        for (int j = 3; j + 3 < reply.length; j += 4) {
                            ecu.faults.add(String.format(Locale.ROOT, "%02X%02X%02X (status %02X)",
                                    reply[j] & 255, reply[j+1] & 255, reply[j+2] & 255, reply[j+3] & 255));
                        }
                        ecu.faultStatus = ecu.faults.isEmpty() ? "შეცდომა არ დაბრუნდა" :
                                ecu.faults.size() + " კოდი";
                    }
                } else if (isNegative(reply, 0x19)) {
                    ecu.faultStatus = "არ არის მხარდაჭერილი (NRC " + hexByte(reply[2] & 255) + ")";
                } else {
                    ecu.faultStatus = "პასუხის ფორმატი უცნობია";
                }
                try { Thread.sleep(35); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IOException("წაკითხვა შეწყვეტილია");
                }
            }
        }
        progress.onProgress(modules.size(), modules.size(), "შეცდომების წაკითხვა დასრულდა");
    }

    public static String report(Vehicle vehicle, List<EcuInfo> modules, boolean includeVin) {
        StringBuilder b = new StringBuilder("KODA — BMW ENET ECU report (READ-ONLY)\n");
        b.append("Protocol: ").append(vehicle.protocol).append("\nGateway: ")
            .append(vehicle.address).append("\nVIN: ")
            .append(includeVin ? vehicle.vin : maskVin(vehicle.vin)).append("\n")
            .append("Found ECUs: ").append(modules.size()).append("\n\n");
        for (EcuInfo m : modules) {
            b.append(m.addressText()).append(" ").append(m.label).append("\n")
                .append("  Identification: ").append(m.identified ? "positive" : "negative UDS response only").append("\n");
            if (!m.partNumber.isEmpty()) b.append("  Spare part / ID: ").append(m.partNumber).append("\n");
            if (!m.vin.isEmpty()) b.append("  ECU VIN: ").append(includeVin ? m.vin : maskVin(m.vin)).append("\n");
            b.append("  DTC read: ").append(m.faultStatus).append("\n");
            for (String fault : m.faults) b.append("    ").append(fault).append("\n");
        }
        b.append("\nTarget list is intentionally limited; unlisted ECU does not mean absent. ")
            .append("Raw codes are not translated BMW fault descriptions.\n");
        return b.toString();
    }
    private static String maskVin(String vin) {
        if (vin == null || vin.length() < 6) return "—";
        return "***********" + vin.substring(vin.length() - 6);
    }
    private static String hexByte(int addr) { return String.format(Locale.ROOT, "0x%02X", addr & 255); }
    private static String printable(byte[] data, int start) {
        StringBuilder b = new StringBuilder();
        for (int i = start; i < data.length && i - start < 80; i++) {
            int v = data[i] & 255;
            if (v >= 32 && v <= 126) b.append((char) v);
            else b.append(String.format(Locale.ROOT, "%02X", v));
        }
        return b.toString().trim();
    }
    public static boolean isDidResponse(byte[] uds, int did) {
        return uds != null && uds.length > 3 && (uds[0] & 255) == 0x62 &&
                (uds[1] & 255) == ((did >> 8) & 255) && (uds[2] & 255) == (did & 255);
    }
    public static boolean isNegative(byte[] uds, int service) {
        return uds != null && uds.length >= 3 && (uds[0] & 255) == 0x7F &&
                (uds[1] & 255) == service && (uds[2] & 255) != 0x78;
    }
    public static String moduleHint(int address) {
        switch (address) {
            case 0x10: return "Gateway / ZGW";
            case 0x12: return "ძრავის მართვა · DME/DDE (შესაძლო)";
            case 0x18: return "ტრანსმისია ან ძრავის ECU (შესაძლო)";
            case 0x29: return "სტაბილიზაცია · DSC (შესაძლო)";
            case 0x30: return "საჭის მართვა · EPS (შესაძლო)";
            case 0x40: return "კორპუსის მართვა · BDC/FEM (შესაძლო)";
            case 0x60: return "ინსტრუმენტთა პანელი (შესაძლო)";
            case 0x63: return "მულტიმედია · Head Unit (შესაძლო)";
            case 0x78: return "კლიმატ-კონტროლი (შესაძლო)";
            case 0x01: return "უსაფრთხოების სისტემა (შესაძლო)";
            default: return "ECU მოდული · ტიპი დაუდგენელია";
        }
    }

    private final class Session implements AutoCloseable {
        private final Socket socket;
        private final Vehicle vehicle;
        private final InputStream in;
        private final OutputStream out;
        Session(Vehicle v) throws IOException {
            vehicle = v;
            int port = "HSFZ".equals(v.protocol) ? 6801 : 13400;
            if (!"HSFZ".equals(v.protocol) && !"DoIP".equals(v.protocol))
                throw new IOException("უცნობი პროტოკოლი: " + v.protocol);
            socket = link.openTcp(v.address, port);
            socket.setSoTimeout(800);
            in = socket.getInputStream(); out = socket.getOutputStream();
            if ("DoIP".equals(v.protocol)) activate();
        }
        private void activate() throws IOException {
            out.write(EnetProtocol.routingActivation(vehicle.version, TESTER)); out.flush();
            for (int i = 0; i < 5; i++) {
                Packet p = EnetProtocol.readDoip(in);
                if (p.type == 0x0006 && p.payload.length >= 5) {
                    if (EnetProtocol.unsignedShort(p.payload, 0) != TESTER || (p.payload[4] & 255) != 0x10)
                        throw new IOException("DoIP activation rejected");
                    return;
                }
            }
            throw new IOException("DoIP activation response არ მოვიდა");
        }
        /** Returns matching UDS payload or null for timeout/unavailable target. */
        byte[] request(int address, byte[] uds, int milliseconds) throws IOException {
            if (address == 0xDF || address >= 0xF0) return null; // never functional broadcast
            byte[] frame;
            if ("HSFZ".equals(vehicle.protocol)) {
                byte[] body = new byte[2 + uds.length];
                body[0] = (byte)0xF4; body[1] = (byte)address;
                System.arraycopy(uds, 0, body, 2, uds.length);
                frame = EnetProtocol.hsfz(0x0001, body);
            } else {
                byte[] body = new byte[4 + uds.length];
                body[0] = (byte)(TESTER >> 8); body[1] = (byte)TESTER;
                body[2] = (byte)(address >> 8); body[3] = (byte)address;
                System.arraycopy(uds, 0, body, 4, uds.length);
                frame = EnetProtocol.doip(vehicle.version, 0x8001, body);
            }
            out.write(frame); out.flush();
            long until = System.nanoTime() + milliseconds * 1_000_000L;
            while (System.nanoTime() < until) {
                int remaining = (int)Math.max(1, (until - System.nanoTime()) / 1_000_000L);
                socket.setSoTimeout(remaining);
                try {
                    Packet p = "HSFZ".equals(vehicle.protocol) ? EnetProtocol.readHsfz(in) : EnetProtocol.readDoip(in);
                    byte[] response = matched(p, address);
                    if (response == null) continue;
                    if (response.length >= 3 && (response[0] & 255) == 0x7F &&
                        (response[1] & 255) == (uds[0] & 255) && (response[2] & 255) == 0x78) {
                        continue; // response pending, wait until bounded deadline
                    }
                    if ((response.length >= 1 && (response[0] & 255) == ((uds[0] & 255) + 0x40)) ||
                        (response.length >= 3 && (response[0] & 255) == 0x7F && (response[1] & 255) == (uds[0] & 255)))
                        return response;
                } catch (SocketTimeoutException timeout) { return null; }
            }
            return null;
        }
        private byte[] matched(Packet p, int addr) {
            if ("HSFZ".equals(vehicle.protocol)) {
                if (p.type != 0x0001 || p.payload.length < 3) return null;
                if ((p.payload[0] & 255) != addr || (p.payload[1] & 255) != 0xF4) return null;
                return Arrays.copyOfRange(p.payload, 2, p.payload.length);
            }
            if (p.type != 0x8001 || p.payload.length < 5) return null;
            if (EnetProtocol.unsignedShort(p.payload, 0) != addr ||
                EnetProtocol.unsignedShort(p.payload, 2) != TESTER) return null;
            return Arrays.copyOfRange(p.payload, 4, p.payload.length);
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
