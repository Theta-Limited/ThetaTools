// CSV-to-CoT.java
// Send CoT event messages using CSV input

// Matthew Krupczak
// matthew.krupczak@theta.limited
// Theta Limited


// CC0 1.0
// https://creativecommons.org/publicdomain/zero/1.0/deed.en

// This program takes CSV files exported by the SW Maps app on Android
// and dumps all of the recorded point locations to ATAK using CoT messages


import org.w3c.dom.CDATASection;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class CSVtoCoT {
    private static long eventuid = 0;
    private static TimeZone tz = TimeZone.getTimeZone("UTC");
    private static DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java CursorOnTargetSender <CSV file path>");
            System.exit(-1);
        }

        String csvFile = args[0];
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            // Skip header
            br.readLine();
            long line_num = 0;
            while ((line = br.readLine()) != null) {
                line_num++;
                String[] values = line.split(",");
                // double lat = Double.parseDouble(values[4]);
                // double lon = Double.parseDouble(values[5]);
                // double hae = Double.parseDouble(values[6]); // Using Elevation column for WGS84 hae
                // double ce = Double.parseDouble(values[12]); // Horizontal Accuracy
                // double le = Double.parseDouble(values[13]); // Vertical Accuracy
                // String uid_suffix = values[2];
                double lat = Double.parseDouble(values[2]);
                double lon = Double.parseDouble(values[3]);
                double hae = Double.parseDouble(values[4]);
                double ce = Double.parseDouble(values[5]);
                double le = 5.1; // Vertical Accuracy
                String uid_suffix = String.valueOf(line_num);
                if (uid_suffix == null) {
                    uid_suffix = Long.valueOf(eventuid).toString();
                }
                sendCoT(lat, lon, hae, ce, le, uid_suffix);
            }
            System.out.println("");
            System.out.println("All points transmitted without error");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void sendCoT(double lat, double lon, double hae, double ce, double le, String uid_suffix) {
        df.setTimeZone(tz);
        Date now = new Date();
        String nowAsISO = df.format(now);
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.MINUTE, 180);
        Date staleTime = cal.getTime();
        String staleTimeISO = df.format(staleTime);

        new Thread(() -> {
                String uidString = "thetalimited-" /*+ getDeviceHostnameHash() + "-"*/ + uid_suffix;
            String xmlString = buildCoT(uidString, nowAsISO, staleTimeISO, Double.toString(lat), Double.toString(lon), Double.toString(ce), Double.toString(hae), Double.toString(le));
            deliverUDP(xmlString); // increments uid upon success
            System.out.println(xmlString);
        }).start();
    }

    public static String buildCoT(String uid, String nowAsISO, String staleTimeISO, String lat, String lon, String ce, String hae, String le) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.newDocument();

            Element root = doc.createElement("event");
            root.setAttribute("version", "2.0");
            root.setAttribute("uid", uid);
            root.setAttribute("type", "a-h-G");
            root.setAttribute("how", "h-c");
            root.setAttribute("time", nowAsISO);
            root.setAttribute("start", nowAsISO);
            root.setAttribute("stale", staleTimeISO);
            doc.appendChild(root);

            Element point = doc.createElement("point");
            point.setAttribute("lat", lat);
            point.setAttribute("lon", lon);
            point.setAttribute("ce", ce);
            point.setAttribute("hae", hae);
            point.setAttribute("le", le);
            root.appendChild(point);

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty("standalone", "yes");
            DOMSource source = new DOMSource(doc);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            transformer.transform(source, result);
            return writer.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private static void deliverUDP(String xml) {
        new Thread(() -> {
            try {
                String message = xml;
                InetAddress address = InetAddress.getByName("239.2.3.1");
                int port = 6969;

                DatagramSocket socket = new DatagramSocket();
                DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length(), address, port);
                socket.send(packet);
                socket.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static String getDeviceHostnameHash() {
        return "Ground-Control-Point";
        // try {
        //     InetAddress addr = InetAddress.getLocalHost();
        //     String hostname = addr.getHostName();
        //     MessageDigest md = MessageDigest.getInstance("SHA-256");
        //     md.update(hostname.getBytes());
        //     byte[] digest = md.digest();
        //     StringBuilder sb = new StringBuilder();
        //     for (byte b : digest) {
        //         sb.append(String.format("%02x", b & 0xff));
        //     }
        //     return sb.toString();
        // } catch (Exception e) {
        //     return "unknown";
        // }
    }
}
