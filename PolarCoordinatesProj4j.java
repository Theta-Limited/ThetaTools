// PolarGridWithProj4J.java
// Requires: proj4j-core and proj4j-epsg on the classpath.
// What it does (Origin -> Target):
//   • Projects both points to UTM (same zone as the origin) using Proj4J
//   • GRID azimuth from UTM vector: atan2(ΔE, ΔN)
//   • TRUE azimuth + ellipsoidal ground distance via Vincenty (self-contained)
//   • Slant range + elevation angle using Δh
//   • MGRS strings (1 m) built from Zone/Band and E/N from the Proj4J projection
//
// Build & run:
//   javac -cp ".:proj4j-core.jar:proj4j-epsg.jar" PolarGridWithProj4J.java
//   java  -cp ".:proj4j-core.jar:proj4j-epsg.jar" PolarGridWithProj4J lat0 lon0 h0  lat1 lon1 h1

import org.locationtech.proj4j.*;

public class PolarGridWithProj4J
{
    // ===== WGS-84 =====
    static final double A  = 6378137.0;
    static final double F  = 1.0 / 298.257223563;
    static final double B  = A * (1.0 - F);

    public static final class Result {
        // TRUE azimuth (from geodesic)
        public final double trueAzDeg, trueAzMils6400, trueAzMrad;
        public final double groundMeters;

        // GRID via Proj4J (UTM)
        public final int utmZone; public final boolean utmNorth;
        public final double e0, n0, e1, n1; // UTM coords
        public final double gridAzDeg, gridAzMils6400, gridAzMrad;
        public final double utmPlanarMeters; // planar distance √(ΔE²+ΔN²)

        // Vertical
        public final double slantMeters, elevationDeg;

        // MGRS (1 m)
        public final String mgrsOrigin, mgrsTarget;

        Result(double trueAzDeg, double ground,
               int utmZone, boolean utmNorth,
               double e0, double n0, double e1, double n1,
               double gridAzDeg, double utmPlanarMeters,
               double slant, double elev,
               String mgrs0, String mgrs1) {
            this.trueAzDeg = trueAzDeg;
            this.trueAzMils6400 = trueAzDeg * 6400.0 / 360.0;
            this.trueAzMrad = Math.toRadians(trueAzDeg) * 1000.0;
            this.groundMeters = ground;

            this.utmZone = utmZone; this.utmNorth = utmNorth;
            this.e0 = e0; this.n0 = n0; this.e1 = e1; this.n1 = n1;

            this.gridAzDeg = norm360(gridAzDeg);
            this.gridAzMils6400 = this.gridAzDeg * 6400.0 / 360.0;
            this.gridAzMrad = Math.toRadians(this.gridAzDeg) * 1000.0;
            this.utmPlanarMeters = utmPlanarMeters;

            this.slantMeters = slant;
            this.elevationDeg = elev;

            this.mgrsOrigin = mgrs0;
            this.mgrsTarget = mgrs1;
        }
    }

    public static Result compute(double lat0, double lon0, double h0,
                                 double lat1, double lon1, double h1) {

        // —— 1) TRUE azimuth + ellipsoidal ground distance (Vincenty)
        double[] inv = vincentyInverse(lat0, lon0, lat1, lon1); // {s, azTrueDeg}
        double ground = inv[0];
        double trueAz = inv[1];

        // —— 2) Project both points to UTM using Proj4J (origin’s zone)
        int zone = utmZoneFromLonLat(lon0, lat0);              // includes Norway/Svalbard exceptions
        boolean northHem = lat0 >= 0.0;
        String utmEpsg = (northHem ? "EPSG:326" : "EPSG:327") + String.format("%02d", zone);

        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem wgs84 = crsFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem utm   = crsFactory.createFromName(utmEpsg);

        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        CoordinateTransform toUTM = ctFactory.createTransform(wgs84, utm);

        ProjCoordinate p0 = new ProjCoordinate(lon0, lat0);
        ProjCoordinate p1 = new ProjCoordinate(lon1, lat1);
        ProjCoordinate u0 = new ProjCoordinate();
        ProjCoordinate u1 = new ProjCoordinate();
        toUTM.transform(p0, u0);
        toUTM.transform(p1, u1);

        double dE = u1.x - u0.x, dN = u1.y - u0.y;
        double gridAz = Math.toDegrees(Math.atan2(dE, dN)); // clockwise from grid north
        double utmPlanar = Math.hypot(dE, dN);

        // —— 3) Vertical (slant & elevation)
        double dh = h1 - h0;
        double slant = Math.hypot(ground, dh);
        double elevDeg = (ground == 0.0)
                ? (dh > 0 ? 90.0 : (dh < 0 ? -90.0 : 0.0))
                : Math.toDegrees(Math.atan2(dh, ground));

        // —— 4) MGRS strings (1 m) from zone/band and (E,N) — using Proj4J E/N
        String mgrs0 = toMGRSFromUTM(lat0, zone, northHem, u0.x, u0.y, 5);
        String mgrs1 = toMGRSFromUTM(lat1, zone, northHem, u1.x, u1.y, 5);

        return new Result(trueAz, ground, zone, northHem, u0.x, u0.y, u1.x, u1.y,
                          gridAz, utmPlanar, slant, elevDeg, mgrs0, mgrs1);
    }

    // ===== CLI =====
    public static void main(String[] args) {
        if (args.length != 6) {
            System.out.println("Usage: java PolarGridWithProj4J lat0 lon0 h0  lat1 lon1 h1");
            System.out.println("  lat/lon: decimal degrees (WGS-84); h: meters AMSL");
            return;
        }
        double lat0 = Double.parseDouble(args[0]);
        double lon0 = Double.parseDouble(args[1]);
        double h0   = Double.parseDouble(args[2]);
        double lat1 = Double.parseDouble(args[3]);
        double lon1 = Double.parseDouble(args[4]);
        double h1   = Double.parseDouble(args[5]);

        Result r = compute(lat0, lon0, h0, lat1, lon1, h1);

        System.out.println("— Inputs —");
        System.out.printf("Origin: lat=%.6f lon=%.6f h=%.2f m AMSL%n", lat0, lon0, h0);
        System.out.printf("Target: lat=%.6f lon=%.6f h=%.2f m AMSL%n", lat1, lon1, h1);

        System.out.println("\n— MGRS —");
        System.out.println("Origin MGRS: " + (r.mgrsOrigin == null ? "(outside UTM bands)" : r.mgrsOrigin));
        System.out.println("Target MGRS: " + (r.mgrsTarget == null ? "(outside UTM bands)" : r.mgrsTarget));

        System.out.println("\n— TRUE North —");
        System.out.println("Definition: Bearing is CLOCKWISE from TRUE (geographic) north; 0° at true north.");
        System.out.printf("Azimuth TRUE (deg):      %.6f%n", r.trueAzDeg);
        System.out.printf("Azimuth TRUE (mils6400): %.2f%n", r.trueAzMils6400);
        System.out.printf("Azimuth TRUE (mrad):     %.6f%n", r.trueAzMrad);
        System.out.printf("Ground distance (m):     %.3f%n", r.groundMeters);

        System.out.println("\n— GRID North (UTM via Proj4J) —");
        System.out.println("Definition: Bearing is CLOCKWISE from GRID north (UTM grid at origin).");
        System.out.printf("UTM Zone: %d%s  (EPSG:%s)%n", r.utmZone, (r.utmNorth ? "N" : "S"),
                (r.utmNorth ? "326" : "327") + String.format("%02d", r.utmZone));
        System.out.printf("Origin UTM (E,N): (%.3f, %.3f) m%n", r.e0, r.n0);
        System.out.printf("Target UTM (E,N): (%.3f, %.3f) m%n", r.e1, r.n1);
        System.out.printf("Azimuth GRID (deg):      %.6f%n", r.gridAzDeg);
        System.out.printf("Azimuth GRID (mils6400): %.2f%n", r.gridAzMils6400);
        System.out.printf("Azimuth GRID (mrad):     %.6f%n", r.gridAzMrad);
        System.out.printf("UTM planar distance (m): %.3f%n", r.utmPlanarMeters);

        System.out.println("\n— Vertical —");
        System.out.printf("Slant range (m):         %.3f%n", r.slantMeters);
        System.out.printf("Elevation angle (deg):   %.6f%n", r.elevationDeg);

        System.out.println("\nNotes:");
        System.out.println("• TRUE distance/azimuth are ellipsoidal (Vincenty).");
        System.out.println("• GRID azimuth and planar distance use Proj4J UTM projection.");
        System.out.println("• For best fidelity, keep both points in the same UTM zone and ranges ≲ 50 km.");
    }

    // ==================== Proj4J helpers ====================

    // MGRS from Zone/Band and projected E/N (1 m..100 km by precisionDigits 0..5)
    private static String toMGRSFromUTM(double latDeg, int zone, boolean northHem,
                                        double easting, double northing, int precisionDigits) {
        if (latDeg < -80 || latDeg >= 84) return null; // UPS not implemented
        if (precisionDigits < 0) precisionDigits = 0;
        if (precisionDigits > 5) precisionDigits = 5;

        char band = latBand(latDeg);
        if (band == 0) return null;

        // 100 km grid square letters
        int e100k = (int)Math.floor(easting / 100000.0);
        int n100k = (int)Math.floor(northing / 100000.0);
        char colLetter = colSetForZone(zone).charAt(Math.max(0, Math.min(7, e100k - 1)));
        String rowSet = "ABCDEFGHJKLMNPQRSTUV"; // 20 letters, no I/O
        int rowOffset = (zone % 2 == 0) ? 5 : 0;
        char rowLetter = rowSet.charAt((n100k + rowOffset) % 20);

        int eR = (int)Math.floor(easting  % 100000.0);
        int nR = (int)Math.floor(northing % 100000.0);
        int div = (int)Math.round(Math.pow(10, 5 - precisionDigits));
        eR = eR / div;
        nR = nR / div;

        String eStr = String.format("%0" + precisionDigits + "d", eR);
        String nStr = String.format("%0" + precisionDigits + "d", nR);

        return zone + String.valueOf(band) + " " + colLetter + rowLetter + " " + eStr + nStr;
    }

    private static char latBand(double lat) {
        if (lat < -80 || lat >= 84) return 0;
        final char[] bands = {'C','D','E','F','G','H','J','K','L','M','N','P','Q','R','S','T','U','V','W','X'};
        int idx = (int)Math.floor((lat + 80.0) / 8.0);
        if (idx < 0) idx = 0;
        if (idx > 19) idx = 19;
        return bands[idx];
    }
    private static String colSetForZone(int zone) {
        switch ((zone - 1) % 3) {
            case 0:  return "ABCDEFGH";
            case 1:  return "JKLMNPQR";
            default: return "STUVWXYZ";
        }
    }

    // UTM zone with Norway/Svalbard exceptions (for consistency with MGRS)
    private static int utmZoneFromLonLat(double lonDeg, double latDeg) {
        int zone = (int)Math.floor((lonDeg + 180.0) / 6.0) + 1;
        if (latDeg >= 56.0 && latDeg < 64.0 && lonDeg >= 3.0 && lonDeg < 12.0) zone = 32; // Norway
        if (latDeg >= 72.0 && latDeg < 84.0) { // Svalbard
            if      (lonDeg >= 0.0  && lonDeg < 9.0 ) zone = 31;
            else if (lonDeg >= 9.0  && lonDeg < 21.0) zone = 33;
            else if (lonDeg >= 21.0 && lonDeg < 33.0) zone = 35;
            else if (lonDeg >= 33.0 && lonDeg < 42.0) zone = 37;
        }
        if (zone < 1) zone = 1;
        if (zone > 60) zone = 60;
        return zone;
    }

    // ==================== Geodesic (Vincenty) ====================
    // Returns { distance s (m), initial TRUE azimuth at origin (deg) }.
    private static double[] vincentyInverse(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        final double phi1 = Math.toRadians(lat1Deg);
        final double phi2 = Math.toRadians(lat2Deg);
        final double L    = Math.toRadians(lon2Deg - lon1Deg);

        if (Math.abs(lat1Deg - lat2Deg) < 1e-15 && Math.abs(lon1Deg - lon2Deg) < 1e-15)
            return new double[]{0.0, 0.0};

        final double U1 = Math.atan((1.0 - F) * Math.tan(phi1));
        final double U2 = Math.atan((1.0 - F) * Math.tan(phi2));
        final double sinU1 = Math.sin(U1), cosU1 = Math.cos(U1);
        final double sinU2 = Math.sin(U2), cosU2 = Math.cos(U2);

        double lambda = L, lambdaPrev;
        final int MAX = 200;
        int it = 0;

        double sinSigma, cosSigma, sigma, sinAlpha, cos2Alpha, cos2SigmaM;

        do {
            lambdaPrev = lambda;
            double sinL = Math.sin(lambda), cosL = Math.cos(lambda);

            double t1 = cosU2 * sinL;
            double t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosL;

            sinSigma = Math.sqrt(t1*t1 + t2*t2);
            if (sinSigma == 0) return new double[]{0.0, 0.0};
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosL;
            sigma = Math.atan2(sinSigma, cosSigma);

            sinAlpha = (cosU1 * cosU2 * sinL) / sinSigma;
            cos2Alpha = 1.0 - sinAlpha * sinAlpha;

            cos2SigmaM = (cos2Alpha == 0) ? 0 : (cosSigma - 2.0 * sinU1 * sinU2 / cos2Alpha);

            double C = (F / 16.0) * cos2Alpha * (4.0 + F * (4.0 - 3.0 * cos2Alpha));
            lambda = L + (1.0 - C) * F * sinAlpha *
                    (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1.0 + 2.0 * cos2SigmaM*cos2SigmaM)));
        } while (Math.abs(lambda - lambdaPrev) > 1e-12 && ++it < MAX);

        if (it >= MAX) {
            // Spherical fallback
            final double R = 6371008.8;
            double dPhi = phi2 - phi1;
            double a = Math.sin(dPhi/2)*Math.sin(dPhi/2)
                    + Math.cos(phi1)*Math.cos(phi2)*Math.sin(L/2)*Math.sin(L/2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
            double dist = R * c;
            double y = Math.sin(L) * Math.cos(phi2);
            double x = Math.cos(phi1)*Math.sin(phi2) - Math.sin(phi1)*Math.cos(phi2)*Math.cos(L);
            double brg = norm360(Math.toDegrees(Math.atan2(y, x)));
            return new double[]{dist, brg};
        }

        double uSq = (1.0 - cos2Alpha) * (A*A - B*B) / (B*B);
        double Acoef = 1.0 + uSq/16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)));
        double Bcoef = uSq/1024.0  * (256.0  + uSq * (-128.0 + uSq * (74.0  - 47.0  * uSq)));
        double deltaSigma = Bcoef * sinSigma *
                (cos2SigmaM + Bcoef/4.0 * (cosSigma * (-1.0 + 2.0 * cos2SigmaM*cos2SigmaM)
                - Bcoef/6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma*sinSigma)
                * (-3.0 + 4.0 * cos2SigmaM*cos2SigmaM)));

        double s = B * Acoef * (sigma - deltaSigma);

        double alpha1 = Math.atan2(Math.cos(U2) * Math.sin(lambda),
                Math.cos(U1) * Math.sin(U2) - Math.sin(U1) * Math.cos(U2) * Math.cos(lambda));
        double azDeg = norm360(Math.toDegrees(alpha1));

        return new double[]{s, azDeg};
    }

    // ==================== Misc ====================
    private static double norm360(double deg) {
        double x = deg % 360.0;
        if (x < 0) x += 360.0;
        return x;
    }
}
