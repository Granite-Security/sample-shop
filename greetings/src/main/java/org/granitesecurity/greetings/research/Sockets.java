package org.granitesecurity.greetings.research;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteOrder;
import static java.lang.foreign.ValueLayout.*;

public class Sockets {

    static final int AF_INET       = 2;
    static final int SOCK_RAW      = 3;
    static final int SOCK_DGRAM    = 2;
    static final int IPPROTO_ICMP  = 1;
    static final int ICMP_ECHO     = 8;
    static final int SOL_SOCKET    = 0xFFFF;   // macOS value; Linux uses 1
    static final int SO_RCVTIMEO   = 0x1006;   // macOS value; Linux uses 20

    // struct timeval on 64-bit macOS: 8-byte tv_sec, 4-byte tv_usec, 4 padding.
    static final StructLayout TIMEVAL = MemoryLayout.structLayout(
            JAVA_LONG.withName("tv_sec"),
            JAVA_INT.withName("tv_usec"),
            MemoryLayout.paddingLayout(4)
    ).withName("timeval");

    // struct sockaddr_in on macOS (BSD): the leading sin_len byte is BSD-only.
    static final StructLayout SOCKADDR_IN = MemoryLayout.structLayout(
            JAVA_BYTE.withName("sin_len"),
            JAVA_BYTE.withName("sin_family"),
            JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN).withName("sin_port"),
            JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withName("sin_addr"),
            MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("sin_zero")
    ).withName("sockaddr_in");

    public static void main(String[] args) throws Throwable {
        // usage: [--raw] [host]   e.g.  --raw 8.8.8.8   |   dns.google   |   (default 127.0.0.1)
        boolean raw = false;
        String host = "127.0.0.1";
//        String host = "8.8.8.8";
        for (String a : args) {
            if (a.equals("--raw")) raw = true;
            else host = a;
        }

        // InetAddress does the parsing and DNS for us; we only need the 4 bytes.
        InetAddress addr = InetAddress.getByName(host);
        if (!(addr instanceof Inet4Address)) {
            throw new IllegalArgumentException(host + " resolved to a non-IPv4 address ("
                    + addr.getHostAddress() + "); this demo speaks ICMPv4 only");
        }
        byte[] quad = addr.getAddress();
        int dstAddr = ((quad[0] & 0xFF) << 24) | ((quad[1] & 0xFF) << 16)
                    | ((quad[2] & 0xFF) << 8)  |  (quad[3] & 0xFF);
        System.out.println("target: " + host + " -> " + addr.getHostAddress());

        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();

        Linker.Option capture = Linker.Option.captureCallState("errno");
        StructLayout capLayout = Linker.Option.captureStateLayout();
        VarHandle errnoVh = capLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"));

        MethodHandle socket = linker.downcallHandle(libc.findOrThrow("socket"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), capture);
        MethodHandle sendto = linker.downcallHandle(libc.findOrThrow("sendto"),
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT,
                        ADDRESS, JAVA_INT), capture);
        MethodHandle recvfrom = linker.downcallHandle(libc.findOrThrow("recvfrom"),
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT,
                        ADDRESS, ADDRESS), capture);
        MethodHandle setsockopt = linker.downcallHandle(libc.findOrThrow("setsockopt"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        ADDRESS, JAVA_INT), capture);
        MethodHandle close = linker.downcallHandle(libc.findOrThrow("close"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        MethodHandle strerror = linker.downcallHandle(libc.findOrThrow("strerror"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cap = arena.allocate(capLayout);

            int type = raw ? SOCK_RAW : SOCK_DGRAM;
            int fd = (int) socket.invokeExact(cap, AF_INET, type, IPPROTO_ICMP);
            if (fd < 0) {
                int e = (int) errnoVh.get(cap, 0L);
                throw new RuntimeException("socket() failed: errno=" + e
                        + " (" + err(strerror, e) + ")"
                        + (e == 1 ? " — SOCK_RAW needs root; run with sudo, or drop --raw" : ""));
            }
            System.out.println("socket created: fd=" + fd
                    + " type=" + (raw ? "SOCK_RAW" : "SOCK_DGRAM"));

            // Without this, recvfrom() blocks forever when the host never answers.
            MemorySegment tv = arena.allocate(TIMEVAL);
            tv.set(JAVA_LONG, TIMEVAL.byteOffset(path("tv_sec")), 3L);
            tv.set(JAVA_INT,  TIMEVAL.byteOffset(path("tv_usec")), 0);
            int so = (int) setsockopt.invokeExact(cap, fd, SOL_SOCKET, SO_RCVTIMEO,
                    tv, (int) TIMEVAL.byteSize());
            if (so < 0) {
                int e = (int) errnoVh.get(cap, 0L);
                throw new RuntimeException("setsockopt(SO_RCVTIMEO) failed: " + err(strerror, e));
            }

            // --- destination ---
            MemorySegment dst = arena.allocate(SOCKADDR_IN);
            dst.set(JAVA_BYTE, SOCKADDR_IN.byteOffset(path("sin_len")), (byte) SOCKADDR_IN.byteSize());
            dst.set(JAVA_BYTE, SOCKADDR_IN.byteOffset(path("sin_family")), (byte) AF_INET);
            dst.set(JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN),
                    SOCKADDR_IN.byteOffset(path("sin_addr")), dstAddr);

            // --- ICMP echo request: type, code, checksum, id, seq, payload ---
            byte[] icmp = new byte[16];
            icmp[0] = (byte) ICMP_ECHO;
            icmp[1] = 0;                      // code
            icmp[2] = 0; icmp[3] = 0;         // checksum, filled in below
            int id = (int) (ProcessHandle.current().pid() & 0xFFFF);
            icmp[4] = (byte) (id >> 8); icmp[5] = (byte) id;
            icmp[6] = 0; icmp[7] = 1;         // sequence = 1
            for (int i = 8; i < icmp.length; i++) icmp[i] = (byte) i;
            int ck = checksum(icmp);
            icmp[2] = (byte) (ck >> 8); icmp[3] = (byte) ck;

            MemorySegment out = arena.allocateFrom(JAVA_BYTE, icmp);
            long sent = (long) sendto.invokeExact(cap, fd, out, (long) icmp.length, 0,
                    dst, (int) SOCKADDR_IN.byteSize());
            if (sent < 0) {
                int e = (int) errnoVh.get(cap, 0L);
                throw new RuntimeException("sendto() failed: " + err(strerror, e));
            }
            System.out.println("sent " + sent + " bytes of ICMP echo to " + addr.getHostAddress());

            // --- wait for the echo reply ---
            MemorySegment in = arena.allocate(1500);
            long got = (long) recvfrom.invokeExact(cap, fd, in, 1500L, 0,
                    MemorySegment.NULL, MemorySegment.NULL);
            if (got < 0) {
                int e = (int) errnoVh.get(cap, 0L);
                // EAGAIN(35) / EWOULDBLOCK is how SO_RCVTIMEO reports "nothing came back".
                if (e == 35) {
                    System.out.println("no reply within 3s (host silent, or ICMP filtered)");
                    int rc0 = (int) close.invokeExact(fd);
                    System.out.println("close() -> " + rc0);
                    return;
                }
                throw new RuntimeException("recvfrom() failed: " + err(strerror, e));
            }
            byte[] reply = in.asSlice(0, got).toArray(JAVA_BYTE);
            System.out.println("received " + got + " bytes: " + hex(reply));

            // A raw socket hands you the IP header too; find where ICMP starts.
            int off = (reply.length > 0 && (reply[0] & 0xF0) == 0x40)
                    ? (reply[0] & 0x0F) * 4 : 0;
            System.out.println("IP header length: " + off + " bytes");
            System.out.println("ICMP type=" + (reply[off] & 0xFF)
                    + " code=" + (reply[off + 1] & 0xFF)
                    + "  (0 = echo reply)");

            int rc = (int) close.invokeExact(fd);
            System.out.println("close() -> " + rc);
        }
    }

    static MemoryLayout.PathElement path(String n) { return MemoryLayout.PathElement.groupElement(n); }

    static String err(MethodHandle strerror, int e) throws Throwable {
        MemorySegment p = (MemorySegment) strerror.invokeExact(e);
        return p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Standard internet checksum (RFC 1071): one's-complement sum of 16-bit words. */
    static int checksum(byte[] b) {
        int sum = 0;
        for (int i = 0; i + 1 < b.length; i += 2)
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        if (b.length % 2 != 0) sum += (b[b.length - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
