import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class mcsendjoin {
    static Inet4Address pickIPv4(NetworkInterface ni) throws Exception {
        for (Enumeration<InetAddress> e = ni.getInetAddresses(); e.hasMoreElements();) {
            InetAddress a = e.nextElement();
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                return (Inet4Address) a;
        }
        return null;
    }

    static void printNic(NetworkInterface ni) throws Exception {
        System.out.println("NIC=" + ni.getName() + " up=" + ni.isUp() +
                " mcast=" + ni.supportsMulticast() + " idx=" + ni.getIndex());
        Inet4Address v4 = pickIPv4(ni);
        System.out.println("  IPv4=" + (v4 != null ? v4.getHostAddress() : "<none>"));
    }

    static void attempt(String label, RunnableX r) {
        System.out.print("[" + label + "] ... ");
        try { r.run(); System.out.println("OK"); }
        catch (Throwable t) {
            System.out.println("FAIL -> " + t);
            t.printStackTrace(System.out);
        }
    }
    interface RunnableX { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java McDiag <ifname> <groupIPv4> <port> [ttl=2] [msg=hello]");
            System.exit(1);
        }
        // Make IPv4 the default (mimic Python)
        System.setProperty("java.net.preferIPv4Stack","true");

        String ifName = args[0];
        InetAddress group = InetAddress.getByName(args[1]);
        int port  = Integer.parseInt(args[2]);
        int ttl   = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        String msg= args.length > 4 ? args[4] : "hello";

        if (!(group instanceof Inet4Address) || !group.isMulticastAddress())
            throw new IllegalArgumentException("Group must be IPv4 multicast (e.g., 239.255.0.1)");

        NetworkInterface ni = NetworkInterface.getByName(ifName);
        if (ni == null) throw new IllegalArgumentException("No such interface: " + ifName);
        printNic(ni);
        Inet4Address srcV4 = pickIPv4(ni);
        if (srcV4 == null) throw new IllegalStateException("No usable IPv4 on " + ifName);

        var dest = new InetSocketAddress(group, port);
        byte[] payload = msg.getBytes(StandardCharsets.UTF_8);

        // A) NIO: set options BEFORE bind, bind wildcard (like Python), no connect
        attempt("A NIO wildcard bind",
            () -> {
                try (DatagramChannel ch = DatagramChannel.open(java.net.StandardProtocolFamily.INET)) {
                    ch.setOption(java.net.StandardSocketOptions.IP_MULTICAST_TTL, ttl);
                    ch.setOption(java.net.StandardSocketOptions.IP_MULTICAST_IF, ni);
                    ch.bind(new InetSocketAddress(0));
                    ch.send(ByteBuffer.wrap(payload), dest);
                }
            });

        // B) NIO: set options BEFORE bind, bind to NIC IPv4
        attempt("B NIO bind to NIC IPv4",
            () -> {
                try (DatagramChannel ch = DatagramChannel.open(java.net.StandardProtocolFamily.INET)) {
                    ch.setOption(java.net.StandardSocketOptions.IP_MULTICAST_TTL, ttl);
                    ch.setOption(java.net.StandardSocketOptions.IP_MULTICAST_IF, ni);
                    ch.bind(new InetSocketAddress(srcV4, 0));
                    ch.send(ByteBuffer.wrap(payload), dest);
                }
            });

        // C) MulticastSocket: bind wildcard, setInterface(address), no connect
        attempt("C MSocket wildcard bind + setInterface(v4)",
            () -> {
                try (MulticastSocket ms = new MulticastSocket(new InetSocketAddress("0.0.0.0", 0))) {
                    ms.setTimeToLive(ttl);
                    ms.setInterface(srcV4); // sets IP_MULTICAST_IF (IPv4)
                    ms.send(new DatagramPacket(payload, payload.length, dest));
                }
            });

        // D) MulticastSocket: bind to NIC IPv4, setInterface(address), no connect
        attempt("D MSocket bind NIC IPv4 + setInterface(v4)",
            () -> {
                try (MulticastSocket ms = new MulticastSocket(new InetSocketAddress(srcV4, 0))) {
                    ms.setTimeToLive(ttl);
                    ms.setInterface(srcV4);
                    ms.send(new DatagramPacket(payload, payload.length, dest));
                }
            });

        // E) Workaround: join -> send -> leave (cheap, then you exit)
        attempt("E MSocket join->send->leave",
            () -> {
                try (MulticastSocket ms = new MulticastSocket(new InetSocketAddress(srcV4, 0))) {
                    ms.setTimeToLive(ttl);
                    ms.setInterface(srcV4);
                    ms.joinGroup(dest, ni);
                    ms.send(new DatagramPacket(payload, payload.length, dest));
                    ms.leaveGroup(dest, ni);
                }
            });

        System.out.println("Done. If all FAIL, it’s environment (VPN/route). If one OK, copy that strategy.");
    }
}
