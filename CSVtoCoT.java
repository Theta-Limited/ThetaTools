// CSVtoCoT.java
// Send CoT event messages using CSV input with automatic CSV type detection

// Matthew Krupczak
// matthew.krupczak@theta.limited
// Theta Limited

// CC0 1.0
// https://creativecommons.org/publicdomain/zero/1.0/deed.en

// This program takes CSV files exported by either the SW Maps app on Android
// ...or previously recorded using CoT_Listener_to_csv_file.py
// and dumps all of the recorded point locations to ATAK using CoT messages
// It automatically detects whether the CSV is a Ground Control Point list
// or a previously captured CoT message and processes it accordingly

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class CSVtoCoT {
    private static long eventuid = 0;
    private static final TimeZone tz = TimeZone.getTimeZone("UTC");
    private static final DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    // Enumeration to represent the type of CSV
    private enum CSVType {
        GROUND_CONTROL_POINT,
        COT_CAPTURE
    }

    // Class to hold the column indices for different CSV types
    private static class CSVFormat {
        int latIndex;
        int lonIndex;
        int haeIndex;
        int ceIndex;
        int leIndex;
        int uidSuffixIndex;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java CSVtoCoT <CSV file path>");
            System.exit(-1);
        }

        String csvFile = args[0];
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String header = br.readLine();
            if (header == null) {
                System.out.println("Empty CSV file.");
                System.exit(-1);
            }

            CSVType type;
            CSVFormat format = new CSVFormat();

            // Detect CSV type based on header contents
            if (isGroundControlPointCSV(header)) {
                type = CSVType.GROUND_CONTROL_POINT;
                // Assign column indices for Ground Control Point CSV
                format.latIndex = 4;
                format.lonIndex = 5;
                format.haeIndex = 8;
                format.ceIndex = 14;
                format.leIndex = 15;
                format.uidSuffixIndex = 1; // Remarks column
                System.out.println("Detected CSV Type: Ground Control Point List");
            } else if (isCoTCaptureCSV(header)) {
                type = CSVType.COT_CAPTURE;
                // Assign column indices for CoT Capture CSV
                format.latIndex = 2;
                format.lonIndex = 3;
                format.haeIndex = 4;
                format.ceIndex = 5;
                format.leIndex = -1; // Fixed value for Vertical Accuracy
                format.uidSuffixIndex = -1; // Use line number as UID suffix
                System.out.println("Detected CSV Type: CoT Capture Messages");
            } else {
                System.out.println("Unknown CSV format. Unable to process.");
                System.exit(-1);
                return; // To satisfy the compiler
            }

            String line;
            long line_num = 0;
            while ((line = br.readLine()) != null) {
                line_num++;
                // Split the line considering possible quoted commas
                String[] values = splitCSVLine(line);

                // Ensure that the line has enough columns
                if (values.length <= Math.max(
                        format.latIndex >= 0 ? format.latIndex : 0,
                        Math.max(format.lonIndex >= 0 ? format.lonIndex : 0,
                                Math.max(format.haeIndex >= 0 ? format.haeIndex : 0,
                                        format.ceIndex >= 0 ? format.ceIndex : 0)))) {
                    System.out.println("Skipping malformed line " + line_num + ": " + line);
                    continue;
                }

                try {
                    double lat = Double.parseDouble(values[format.latIndex].trim());
                    double lon = Double.parseDouble(values[format.lonIndex].trim());
                    double hae = Double.parseDouble(values[format.haeIndex].trim());
                    double ce = Double.parseDouble(values[format.ceIndex].trim());
                    double le;
                    String uid_suffix;

                    if (type == CSVType.GROUND_CONTROL_POINT) {
                        if (values.length <= format.leIndex) {
                            System.out.println("Skipping line " + line_num + " due to insufficient columns for Vertical Accuracy.");
                            continue;
                        }
                        le = Double.parseDouble(values[format.leIndex].trim());
                        uid_suffix = values[format.uidSuffixIndex].trim();
                        if (uid_suffix.isEmpty()) {
                            uid_suffix = String.valueOf(eventuid);
                        }
                    } else { // COT_CAPTURE
                        le = 5.1; // Fixed Vertical Accuracy as per original commented code
                        uid_suffix = String.valueOf(line_num);
                    }

                    sendCoT(lat, lon, hae, ce, le, uid_suffix);
                } catch (NumberFormatException e) {
                    System.out.println("Skipping line " + line_num + " due to number format error: " + e.getMessage());
                } catch (ArrayIndexOutOfBoundsException e) {
                    System.out.println("Skipping line " + line_num + " due to missing columns.");
                }
            }
            System.out.println("\nAll points transmitted without error.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Splits a CSV line into values, handling quoted commas.
     *
     * @param line The CSV line.
     * @return An array of values.
     */
    private static String[] splitCSVLine(String line) {
        // Simple CSV splitting assuming no escaped quotes
        // For more complex CSVs, consider using a library like OpenCSV
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    /**
     * Detects if the CSV is a Ground Control Point list based on header.
     *
     * @param header The header line of the CSV.
     * @return True if it's a Ground Control Point CSV, else false.
     */
    private static boolean isGroundControlPointCSV(String header) {
        return header.contains("ID") && header.contains("Latitude") && header.contains("Longitude");
    }

    /**
     * Detects if the CSV is a CoT Capture Messages list based on header.
     *
     * @param header The header line of the CSV.
     * @return True if it's a CoT Capture CSV, else false.
     */
    private static boolean isCoTCaptureCSV(String header) {
        return header.contains("lat") && header.contains("lon");
    }

    /**
     * Sends a CoT event message with the given parameters.
     *
     * @param lat        Latitude.
     * @param lon        Longitude.
     * @param hae        Height above ellipsoid.
     * @param ce         Circular error.
     * @param le         Linear error (Vertical Accuracy).
     * @param uid_suffix Suffix for the UID.
     */
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
            String xmlString = buildCoT(uidString, nowAsISO, staleTimeISO,
                    Double.toString(lat), Double.toString(lon),
                    Double.toString(ce), Double.toString(hae),
                    Double.toString(le));
            deliverUDP(xmlString); // increments uid upon success
            System.out.println(xmlString);
            eventuid++;
        }).start();
    }

    /**
     * Builds the CoT XML string.
     *
     * @param uid          Unique identifier.
     * @param nowAsISO     Current time in ISO format.
     * @param staleTimeISO Stale time in ISO format.
     * @param lat          Latitude.
     * @param lon          Longitude.
     * @param ce           Circular error.
     * @param hae          Height above ellipsoid.
     * @param le           Linear error.
     * @return The CoT XML string.
     */
    public static String buildCoT(String uid, String nowAsISO, String staleTimeISO,
                                  String lat, String lon, String ce, String hae, String le) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc = db.newDocument();

            // Create root element
            org.w3c.dom.Element root = doc.createElement("event");
            root.setAttribute("version", "2.0");
            root.setAttribute("uid", uid);
            root.setAttribute("type", "a-h-G");
            root.setAttribute("how", "h-c");
            root.setAttribute("time", nowAsISO);
            root.setAttribute("start", nowAsISO);
            root.setAttribute("stale", staleTimeISO);
            doc.appendChild(root);

            // Create point element
            org.w3c.dom.Element point = doc.createElement("point");
            point.setAttribute("lat", lat);
            point.setAttribute("lon", lon);
            point.setAttribute("ce", ce);
            point.setAttribute("hae", hae);
            point.setAttribute("le", le);
            root.appendChild(point);

            // Transform DOM to string
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
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

    /**
     * Delivers the CoT XML string over UDP multicast.
     *
     * @param xml The CoT XML string.
     */
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

    /**
     * Generates a hash of the device hostname.
     * Currently returns a fixed string for simplicity.
     *
     * @return A string representing the device hostname hash.
     */
    private static String getDeviceHostnameHash() {
        return "Ground-Control-Point";
        // Uncomment and implement if unique hashing is needed
        /*
        try {
            InetAddress addr = InetAddress.getLocalHost();
            String hostname = addr.getHostName();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(hostname.getBytes());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
        */
    }
}
