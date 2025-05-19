// CursorOnTargetSender.java
// Send CoT event messages using standard Java
// libraries and tools
// Matthew Krupczak
// matthew.krupczak@theta.limited
// Theta Informatics LLC

// CC0 1.0
// https://creativecommons.org/publicdomain/zero/1.0/deed.en

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

public class CursorOnTargetSender {

    private static long eventuid = System.currentTimeMillis();

    static TimeZone tz = TimeZone.getTimeZone("UTC");
    static DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public static void sendCoT(double lat, double lon, double hae, double theta, String exif_datetime) {
        if (theta > 90) {
            // If camera is facing backwards, use appropriate value for the reverse direction (the supplementary angle of theta)
            theta = 180.0d - theta;
        } else if (lat > 90 || lat < -90){
            throw new IllegalArgumentException("latitude " + lat + " degrees is invalid");
        } else if (lon > 180 || lon < -180) {
            throw new IllegalArgumentException("longitude " + lon + " degrees is invalid");
        } else if (exif_datetime == null) {
            throw new IllegalArgumentException("exif_datetime was null pointer, expected a String");
        }

        df.setTimeZone(tz);
        Date now = new Date();
        String nowAsISO = df.format(now);
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.MINUTE, 5);
        Date fiveMinsFromNow = cal.getTime();
        String fiveMinutesFromNowISO = df.format(fiveMinsFromNow);
        String imageISO = df.format(convert(exif_datetime));
        double linearError = 15.0d / 3.0d; // optimistic estimation of 1 sigma accuracy of altitude
        double circularError = 1.0d / Math.tan(Math.toRadians(theta)) * linearError; // optimistic estimation of 1 sigma accuracy based on angle of camera depression theta

        String le = Double.toString(linearError);
        String ce = Double.toString(circularError);

	//System.out.println("Fixing to send CoT message via new thread");
	
        new Thread(new Runnable() {
            @Override
            public void run() {
                String uidString = "thetalimited-" + getDeviceHostnameHash().substring(0,8) + "-" + Long.toString(eventuid);
//                String xmlString = buildCoT(uidString, imageISO, nowAsISO, fiveMinutesFromNowISO, Double.toString(lat), Double.toString(lon), ce, Double.toString(Math.round(hae)), le);
                String xmlString = buildCoT(uidString, imageISO, nowAsISO, fiveMinutesFromNowISO, Double.toString(lat), Double.toString(lon), ce, Double.toString(hae), le);
//                String dumxml = "<event uid=\"41414141\" type=\"a-u-G\" how=\"h-c\" start=\"2023-01-24T22:16:53Z\" time=\"2023-01-24T22:16:53Z\" stale=\"2023-01-25T22:06:53Z\"><point le=\"0\" ce=\"0\" hae=\"0\" lon=\"0\" lat=\"0\"/></event>";
//                dumxml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + dumxml;

                deliverUDP(xmlString); // increments uid upon success
            }
        }).start();



    }

    /**
     * Converts an ExifInterface time and date tag into a Joda time format
     *
     * @param exif_tag_datetime
     * @return null in case of failure, the date object otherwise
     */
    public static Date convert(String exif_tag_datetime) {
        // EXIF tag contains no time zone data, assume it is same as local time
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()); // default locale of device at application start
        Date outDate;
        try {
            outDate = simpleDateFormat.parse(exif_tag_datetime);
        } catch (ParseException e) {
            outDate = null;
        }
        return outDate;
    }

    public static String getDeviceHostnameHash() {
        InetAddress addr;
        String hash;
        try {
            addr = InetAddress.getLocalHost();
            String hostname = addr.getHostName();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(hostname.getBytes());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            hash = sb.toString();
        } catch (UnknownHostException e) {
            hash = "unknown";
        } catch (NoSuchAlgorithmException e) {
            hash = "unknown";
        }
        return hash;
    }

    public static String buildCoT(String uid, String imageISO, String nowAsISO, String fiveMinutesFromNowISO, String lat, String lon, String ce, String hae, String le) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.newDocument();


            Element root = doc.createElement("event");
            root.setAttribute("version", "2.0");
            root.setAttribute("uid", uid);
            root.setAttribute("type", "a-p-G");
            root.setAttribute("how", "h-c");
            root.setAttribute("time", imageISO);
            root.setAttribute("start", nowAsISO);
            root.setAttribute("stale", fiveMinutesFromNowISO);
            doc.appendChild(root);

            Element point = doc.createElement("point");
            point.setAttribute("lat", lat);
            point.setAttribute("lon", lon);
            point.setAttribute("ce", ce);
            point.setAttribute("hae", hae);
            point.setAttribute("le", le);
            root.appendChild(point);

            Element detail = doc.createElement("detail");
            root.appendChild(detail);

            Element precisionlocation = doc.createElement("precisionlocation");
            precisionlocation.setAttribute("altsrc", "DTED2");
            precisionlocation.setAttribute("geopointsrc", "GPS");
            detail.appendChild(precisionlocation);

            Element remarks = doc.createElement("remarks");
            remarks.setTextContent("Generated by Theta CursorOnTargetSender script");
            detail.appendChild(remarks);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String message = xml;
                    InetAddress address = InetAddress.getByName("239.2.3.1");
                    int port = 6969;

                    MulticastSocket socket = new MulticastSocket();
                    DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length(), address, port);
                    socket.send(packet);
                    socket.close();
                    eventuid++;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private static void usage(String[] args) {
	System.out.println("Usage: java CursorOnTargetSender lat lon alt");
        System.exit(-1);
    }

    public static void main(String[] args)
    {
	if (args.length != 3) {
	    usage(args);
	}

	// get lat, lon, alt, from args
	double lat = Double.parseDouble(args[0]);
	double lon = Double.parseDouble(args[1]);
	double alt = Double.parseDouble(args[2]);
	
	// make up other numbers
	sendCoT(lat, lon, alt, 50, "2022:12:10 16:37:33");
    }
}
