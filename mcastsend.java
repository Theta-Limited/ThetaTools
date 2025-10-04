import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class mcastsend {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java mcastsend <ifname> <groupIPv4> <port> [ttl=2] [message='hello']");
            System.exit(1);
        }
        String ifName   = args[0];                    // e.g., en0
        String groupStr = args[1];                    // e.g., 239.2.3.1
        int port        = Integer.parseInt(args[2]);  // e.g., 5000
        int ttl         = args.length >= 4 ? Integer.parseInt(args[3]) : 2;
        String message  = args.length >= 5 ? args[4] : "hello";

        // Validate group is IPv4 multicast
        InetAddress groupAddr = InetAddress.getByName(groupStr);
        if (!(groupAddr instanceof Inet4Address) || !groupAddr.isMulticastAddress()) {
            throw new IllegalArgumentException("Group must be an IPv4 multicast address, got: " + groupStr);
        }

        // Find the interface by name and a usable IPv4 on it
        NetworkInterface nif = NetworkInterface.getByName(ifName);
        if (nif == null || !nif.isUp() || !nif.supportsMulticast()) {
            throw new IllegalStateException("Interface not usable for multicast: " + ifName);
        }
        Inet4Address srcV4 = findUsableIPv4(nif);
        if (srcV4 == null) {
            throw new IllegalStateException("No non-loopback IPv4 address on " + ifName);
        }

        // Open IPv4 channel, bind to the NIC's IPv4, set TTL and multicast egress NIC, then send
        try (DatagramChannel ch = DatagramChannel.open(java.net.StandardProtocolFamily.INET)) {
            ch.setOption(java.net.StandardSocketOptions.IP_MULTICAST_TTL, ttl); // TTL >= 1
            ch.setOption(java.net.StandardSocketOptions.IP_MULTICAST_IF, nif);  // egress interface
            ch.bind(new InetSocketAddress(srcV4, 0));                           // bind to NIC’s IPv4

	    
            ByteBuffer buf = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            int bytes = ch.send(buf, new InetSocketAddress(groupAddr, port));

            System.out.printf("Sent %d bytes to %s:%d via %s (src=%s)%n",
                    bytes, groupStr, port, nif.getName(), srcV4.getHostAddress());
        }
    }

    private static Inet4Address findUsableIPv4(NetworkInterface ni) throws SocketException {
        for (var e = ni.getInetAddresses(); e.hasMoreElements(); ) {
            InetAddress a = e.nextElement();
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                return (Inet4Address) a;
            }
        }
        return null;
    }
}
