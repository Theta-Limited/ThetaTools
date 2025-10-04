// polarcoordinates.java
// Bobby Krupczak
// with ChatGPT
// take origin and target in GPS
// altitude in AMSL
// calculate polar coordinates from origin

// PolarCoordinates.java
// Compute polar (azimuth, ground range) from an origin to a target using WGS-84,
// plus slant range and elevation angle given AMSL altitudes.
//
// Usage:
//   javac PolarCoordinates.java
//   java PolarCoordinates lat0 lon0 h0  lat1 lon1 h1
// Example:
//   java PolarCoordinates 37.7749 -122.4194 30  37.8044 -122.2711 50
//

// PolarCoordinates.java
// Compute polar (azimuth, ground range) from an origin to a target using WGS-84,
// plus slant range and elevation angle given AMSL altitudes.
// Outputs azimuth in degrees, NATO mils (6400 per circle), and milliradians (mrad).
//
// Usage:
//   javac PolarCoordinates.java
//   java PolarCoordinates lat0 lon0 h0  lat1 lon1 h1
// Example:
//   java PolarCoordinates 37.7749 -122.4194 30  37.8044 -122.2711 50

// lat0,lon0,h0 is origin
// angles are relative to true north, clockwise, in various outputts
// groundMeters is ellipsoidal surface distance
// slantMeters is altitude diff
// elevationDeg is up/down angle from horizontal at origin
// convert between magnetic north and true north
// true = mag + D
// mag = true - D
// D depends on location and date 
// https://www.marines.mil/Portals/1/Publications/MCWP%203-16.1%20Artillery%20Operations.pdf

// In U.S. Army and Marine Corps calls for (indirect) fire, the direction
// you send is normally in mils relative to grid north (not magnetic, not
// true). If you took the reading with a magnetic compass, you convert it
// to mils grid by applying the map’s G-M angle before you transmit.
// Public Intelligence

// USMC Basic Officer Course handout: “OT direction is always expressed
// to the nearest 10 mils grid… Lensatic compass reads mils magnetic; the
// FO converts to mils grid by applying the GM angle.”  Marine Corps
// Training Command

// JFIRE (joint MTTP): “Directions are normally given in mils relative to
// grid north; any other combination may be used but must be specified
// (e.g., ‘180 degrees magnetic’).”  Public Intelligence

// Legacy Army doctrine examples also show “grid azimuth: DIRECTION
// 4360.”  GlobalSecurity

// If you need the rule in one line when converting a compass read: Grid
// = Magnetic ± (G-M angle) — add a westerly G-M, subtract an easterly
// G-M, per the map’s declination diagram.  Marine Corps Training Command

// Bearings & ranges from ORIGIN -> TARGET using WGS-84 lat/lon (+AMSL alt).
// Reports TRUE-NORTH azimuth and MGRS/UTM GRID-NORTH azimuth (deg, mils6400, mrad),
// ground distance (ellipsoidal), slant range (with Δh), elevation angle, and MGRS for both points.
//
// Build & run:
//   javac PolarGridLatLonAlt.java
//   java PolarGridLatLonAlt lat0 lon0 h0  lat1 lon1 h1

// Note on references:
// - TRUE azimuth = clockwise from TRUE (geographic) north, 0° at the geographic North Pole direction.
// - GRID azimuth = clockwise from GRID north of the local MGRS/UTM grid at the origin.
// (Magnetic is different; if you want magnetic, apply declination D: true = mag + D.)
//  Magnetic declination changes with location and date. Supply declination_deg_east from a geomagnetic model
//  (e.g., WMM/IGRF) or a local chart. 
// Sign convention: east-positive. With that, mag = true − D and grid − mag = D − γ.
// https://en.wikipedia.org/wiki/Vincenty%27s_formulae
// https://en.wikipedia.org/wiki/Geographical_distance

// validation: distance and az true (deg) between two lat/lon pairs
// echo "34.0000 -117.0000  34.0500 -116.9500" | geod +ellps=WGS84 -f "%.8f"
// echo "34.0000 -117.0000  34.0500 -116.9500" | invgeod -I +ellps=WGS84 -f "%.8f"
// ./validate_grid_utm_step.sh 34.0000 -117.0000  34.0500 -116.9500
// ./validate_grid_true_minus_gamma.sh 34.0000 -117.0000  34.0500 -116.9500
        
public class PolarCoordinates
{
    // ---- WGS-84 ----
    static final double A  = 6378137.0;
    static final double F  = 1.0 / 298.257223563;
    static final double B  = A * (1.0 - F);
    static final double E2 = F * (2.0 - F);
    static final double EP2 = E2 / (1.0 - E2);
    static final double K0 = 0.9996;

    // ---- Result ----
    public static final class Result {
        // TRUE
        public final double trueAzDeg, trueAzMils6400, trueAzMrad;
        public final double groundMeters;

        // GRID
        public final double gridAzDeg, gridAzMils6400, gridAzMrad;
        public final double gridConvergenceDeg; // γ = true − grid (signed)

        // Vertical
        public final double slantMeters, elevationDeg;

        // MGRS
        public final String mgrsOrigin, mgrsTarget;

        Result(double trueAzDeg, double groundMeters,
               double gridAzDeg, double gridConvergenceDeg,
               double slantMeters, double elevationDeg,
               String mgrsOrigin, String mgrsTarget) {

            this.trueAzDeg = norm360(trueAzDeg);
            this.trueAzMils6400 = this.trueAzDeg * 6400.0 / 360.0;
            this.trueAzMrad = Math.toRadians(this.trueAzDeg) * 1000.0;
            this.groundMeters = groundMeters;

            this.gridAzDeg = norm360(gridAzDeg);
            this.gridAzMils6400 = this.gridAzDeg * 6400.0 / 360.0;
            this.gridAzMrad = Math.toRadians(this.gridAzDeg) * 1000.0;
            this.gridConvergenceDeg = wrap180(gridConvergenceDeg);

            this.slantMeters = slantMeters;
            this.elevationDeg = elevationDeg;

            this.mgrsOrigin = mgrsOrigin;
            this.mgrsTarget = mgrsTarget;
        }
    }

    // ---- Public compute ----
    public static Result compute(double lat0, double lon0, double h0,
                                 double lat1, double lon1, double h1) {
        // 1) Ellipsoidal ground distance & TRUE azimuth (Vincenty inverse)
        double[] inv = vincentyInverse(lat0, lon0, lat1, lon1); // {s, azTrueDeg}
        double ground = inv[0];
        double trueAz = inv[1];

        // 2) GRID azimuth via 1 m geodesic step projected to origin's UTM zone
        final double STEP_M = 1.0;
        double[] stepLL = vincentyDirect(lat0, lon0, trueAz, STEP_M);
        int zone = utmZoneFromLonLat(lon0, lat0); // with Norway/Svalbard tweaks
        UTM u0 = llToUTMInZone(lat0, lon0, zone);
        UTM uS = llToUTMInZone(stepLL[0], stepLL[1], zone);

        double dE = uS.easting  - u0.easting;
        double dN = uS.northing - u0.northing;
        double gridAz = Math.toDegrees(Math.atan2(dE, dN)); // clockwise from GRID north
        double gamma = angDiffSigned(trueAz, gridAz);        // true − grid

        // 3) Slant & elevation
        double dh = h1 - h0;
        double slant = Math.hypot(ground, dh);
        double elevDeg = (ground == 0.0)
                ? (dh > 0 ? 90.0 : (dh < 0 ? -90.0 : 0.0))
                : Math.toDegrees(Math.atan2(dh, ground));

        // 4) MGRS strings (1 m precision) if within UTM bands
        String mgrs0 = toMGRS(lat0, lon0, 5);
        String mgrs1 = toMGRS(lat1, lon1, 5);

        return new Result(trueAz, ground, gridAz, gamma, slant, elevDeg, mgrs0, mgrs1);
    }

    // ---- CLI ----
    public static void main(String[] args)
    {
        if (args.length != 6) {
            System.out.println("Usage: java PolarCoordinatesMGRS lat0 lon0 h0  lat1 lon1 h1");
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

        System.out.println("— Coordinates —");
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

        System.out.println("\n— GRID North (MGRS/UTM) —");
        System.out.println("Definition: Bearing is CLOCKWISE from GRID north (local UTM grid at the origin).");
        System.out.printf("Grid convergence γ (deg): %.6f%n", r.gridConvergenceDeg);
        System.out.printf("Azimuth GRID (deg):       %.6f%n", r.gridAzDeg);
        System.out.printf("Azimuth GRID (mils6400):  %.2f%n", r.gridAzMils6400);
        System.out.printf("Azimuth GRID (mrad):      %.6f%n", r.gridAzMrad);

        System.out.println("\n— Vertical —");
        System.out.printf("Slant range (m):         %.3f%n", r.slantMeters);
        System.out.printf("Elevation angle (deg):   %.6f%n", r.elevationDeg);

        // use some of our functions to compare
        System.out.println("\n-- Internal Haversine Calculations");        
        double range = haversine(lon0, lat0, lon1, lat1, 0 /* XXX ? */ );
        double bearing = haversine_bearing(lon0, lat0, lon1, lat1);
        System.out.printf("Azimuth TRUE (deg):       %.6f%n", bearing);
        System.out.printf("Ground distance (m):      %.3f%n", range);
        
    } // Main 

    // ==================== Geodesy: Vincenty inverse ====================
    // Returns { distance s (m), initial TRUE azimuth (deg) }.
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
            // Spherical fallback (extremely rare near-antipodal)
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

    // ==================== Geodesy: Vincenty direct ====================
    // From (lat1,lon1) with initial azimuth az1Deg and distance s (m) → {lat2Deg, lon2Deg}.
    private static double[] vincentyDirect(double lat1Deg, double lon1Deg, double az1Deg, double s) {
        double a = A, b = B, f = F;
        double alpha1 = Math.toRadians(az1Deg);
        double sinAlpha1 = Math.sin(alpha1), cosAlpha1 = Math.cos(alpha1);

        double tanU1 = (1 - f) * Math.tan(Math.toRadians(lat1Deg));
        double cosU1 = 1 / Math.sqrt(1 + tanU1 * tanU1);
        double sinU1 = tanU1 * cosU1;

        double sigma1 = Math.atan2(tanU1, cosAlpha1);
        double sinAlpha = cosU1 * sinAlpha1;
        double cos2Alpha = 1 - sinAlpha * sinAlpha;
        double uSq = cos2Alpha * (a*a - b*b) / (b*b);
        double Acoef = 1 + uSq/16384 * (4096 + uSq * (-768 + uSq * (320 - 175*uSq)));
        double Bcoef = uSq/1024  * (256  + uSq * (-128 + uSq * (74  - 47*uSq)));

        double sigma = s / (b * Acoef);
        double sigmaP, cos2SigmaM, sinSigma, cosSigma;
        int it = 0;
        do {
            cos2SigmaM = Math.cos(2*sigma1 + sigma);
            sinSigma = Math.sin(sigma);
            cosSigma = Math.cos(sigma);
            double deltaSigma = Bcoef * sinSigma * (cos2SigmaM + Bcoef/4 *
                    (cosSigma * (-1 + 2*cos2SigmaM*cos2SigmaM) -
                     Bcoef/6 * cos2SigmaM * (-3 + 4*sinSigma*sinSigma) *
                     (-3 + 4*cos2SigmaM*cos2SigmaM)));
            sigmaP = sigma;
            sigma = s / (b * Acoef) + deltaSigma;
        } while (Math.abs(sigma - sigmaP) > 1e-12 && ++it < 200);

        double tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1;
        double lat2 = Math.atan2(sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
                                 (1 - f) * Math.sqrt(sinAlpha*sinAlpha + tmp*tmp));
        double lam = Math.atan2(sinSigma * sinAlpha1,
                                cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1);
        double C = f/16 * cos2Alpha * (4 + f * (4 - 3*cos2Alpha));
        double L = lam - (1 - C) * f * sinAlpha *
                (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1 + 2*cos2SigmaM*cos2SigmaM)));

        double lon2 = Math.toRadians(lon1Deg) + L;
        return new double[] { Math.toDegrees(lat2), Math.toDegrees(lon2) };
    }

    // ==================== UTM forward (general + forced-zone) ====================
    private static final class UTM {
        final int zone; final char hemi; final double easting; final double northing;
        UTM(int zone, char hemi, double easting, double northing) {
            this.zone = zone; this.hemi = hemi; this.easting = easting; this.northing = northing;
        }
    }

    // Standard auto-zone (used for MGRS encoding)
    private static UTM llToUTM(double lat, double lon) {
        int zone = utmZoneFromLonLat(lon, lat);
        return llToUTMInZone(lat, lon, zone);
    }

    // Force projection into a specific zone (used for grid-bearing step)
    private static UTM llToUTMInZone(double lat, double lon, int zone) {
        double lon0 = Math.toRadians(utmCentralMeridianDeg(zone));
        double phi = Math.toRadians(lat);
        double lam = Math.toRadians(lon);

        double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi), tanPhi = Math.tan(phi);
        double N = A / Math.sqrt(1 - E2 * sinPhi * sinPhi);
        double T = tanPhi * tanPhi;
        double C = EP2 * cosPhi * cosPhi;
        double Aterm = (lam - lon0) * cosPhi;

        // Meridional arc
        double e4 = E2 * E2, e6 = e4 * E2;
        double M = A * ((1 - E2/4 - 3*e4/64 - 5*e6/256) * phi
                - (3*E2/8 + 3*e4/32 + 45*e6/1024) * Math.sin(2*phi)
                + (15*e4/256 + 45*e6/1024) * Math.sin(4*phi)
                - (35*e6/3072) * Math.sin(6*phi));

        double A2 = Aterm*Aterm, A3 = A2*Aterm, A4 = A2*A2, A5 = A4*Aterm, A6 = A4*A2;

        double easting = K0 * N * (Aterm + (1 - T + C) * A3/6 + (5 - 18*T + T*T + 72*C - 58*EP2) * A5/120) + 500000.0;
        double northing = K0 * (M + N * tanPhi * (A2/2 + (5 - T + 9*C + 4*C*C) * A4/24 + (61 - 58*T + T*T + 600*C - 330*EP2) * A6/720));
        char hemi = 'N';
        if (lat < 0) { northing += 10000000.0; hemi = 'S'; }

        return new UTM(zone, hemi, easting, northing);
    }

    private static int utmZoneFromLonLat(double lonDeg, double latDeg) {
        int zone = (int)Math.floor((lonDeg + 180.0) / 6.0) + 1;
        // Norway
        if (latDeg >= 56.0 && latDeg < 64.0 && lonDeg >= 3.0 && lonDeg < 12.0) zone = 32;
        // Svalbard
        if (latDeg >= 72.0 && latDeg < 84.0) {
            if      (lonDeg >= 0.0  && lonDeg < 9.0 ) zone = 31;
            else if (lonDeg >= 9.0  && lonDeg < 21.0) zone = 33;
            else if (lonDeg >= 21.0 && lonDeg < 33.0) zone = 35;
            else if (lonDeg >= 33.0 && lonDeg < 42.0) zone = 37;
        }
        if (zone < 1) zone = 1;
        if (zone > 60) zone = 60;
        return zone;
    }
    private static double utmCentralMeridianDeg(int zone) {
        return -183.0 + 6.0 * zone; // λ0 = 6*zone − 183
    }

    // ==================== MGRS (UTM bands only) ====================
    // precisionDigits: 0..5 (5 => 1 m)
    private static String toMGRS(double lat, double lon, int precisionDigits) {
        if (lat < -80 || lat >= 84) return null; // UPS not handled
        if (precisionDigits < 0) precisionDigits = 0;
        if (precisionDigits > 5) precisionDigits = 5;

        UTM utm = llToUTM(lat, lon);
        int zone = utm.zone;
        char band = latBand(lat);
        if (band == 0) return null;

        int e100k = (int)Math.floor(utm.easting / 100000.0);
        int n100k = (int)Math.floor(utm.northing / 100000.0);

        char colLetter = colSetForZone(zone).charAt(Math.max(0, Math.min(7, e100k - 1)));
        String rowSet = "ABCDEFGHJKLMNPQRSTUV";  // 20 letters
        int rowOffset = (zone % 2 == 0) ? 5 : 0; // even zones shift
        char rowLetter = rowSet.charAt((n100k + rowOffset) % 20);

        int eR = (int)Math.floor(utm.easting  % 100000.0);
        int nR = (int)Math.floor(utm.northing % 100000.0);
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

    // ==================== Angle helpers ====================
    private static double norm360(double deg) {
        double x = deg % 360.0; if (x < 0) x += 360.0; return x;
    }
    private static double wrap180(double deg) {
        return ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }
    private static double angDiffSigned(double a, double b) {
        // signed (a - b) wrapped to [-180, 180)
        return wrap180(a - b);
    }
    
   /**
     * Determines the great circle distance between two Lat/Lon pairs
     * <p>
     *     For short distances, this is close to the straight-line distance
     *     adapted from https://stackoverflow.com/a/4913653
     *     Pass 0.0 for alt for simplification for shorter distances between points
     * </p>
     * @param lon1 The longitude of the first point, in degrees
     * @param lat1 The latitude of the first point, in degrees
     * @param lon2 The longitude of the second point, in degrees
     * @param lat2 The latitude of the second point, in degrees
     * @param alt The altitude above the surface of the WGS84 reference ellipsoid, measured in meters.
     * Used to determine the radius of the great circle
     * @return double The distance in meters along a great circle path between the two inputed points.
     */

    public static double haversine(double lon1, double lat1, double lon2, double lat2, double alt)
    {
        lon1 = Math.toRadians(lon1);
        lat1 = Math.toRadians(lat1);
        lon2 = Math.toRadians(lon2);
        lat2 = Math.toRadians(lat2);

        double dlon = lon2 - lon1;
        double dlat = lat2 - lat1;
        double a = squared(sin(dlat/2)) + cos(lat1) * cos(lat2) * squared(sin(dlon/2));
        double c = 2.0d * asin(sqrt(a));
        double r = radius_at_lat_lon((lat1+lat2)/2.0d, (lon1+lon2)/2.0d);
        r = r + alt; // actual height above or below idealized ellipsoid

        return c * r;
    }

    /**
     * Takes two Lat/Lon pairs (a start A and a destination B) and
     * finds the heading of the shortest direction of travel from A to B
     * <p>
     *     Note: this function will work with Geodetic coords of any ellipsoid, provided both pairs are of the same ellipsoid
     *     adapted from https://stackoverflow.com/a/64747209
     * </p>
     * @param lon1
     * @param lat1
     * @param lon2
     * @param lat2
     * @return
     */
    public static double haversine_bearing(double lon1, double lat1, double lon2, double lat2) {
        lon1 = Math.toRadians(lon1);
        lat1 = Math.toRadians(lat1);
        lon2 = Math.toRadians(lon2);
        lat2 = Math.toRadians(lat2);

        double dLon = (lon2 - lon1);
        double x = cos(lat2) * sin(dLon);
        double y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon);

        double brng = atan2(x,y); // arguments intentionally swapped out of order
        brng = radNormalize(brng);
        brng = Math.toDegrees(brng);

        return brng;
    }

    public static double squared(double val) { return val * val; }

    public static double sqrt(double val) { return Math.sqrt(val); }

    public static double sin(double radAngle) { return Math.sin(radAngle); }

    public static double asin(double radAngle) { return Math.asin(radAngle); }

    public static double cos(double radAngle) { return Math.cos(radAngle); }

    public static double atan2(double y, double x) { return Math.atan2(y, x); }

    public static double radNormalize(double radAngle)
    {
        while (radAngle >= Math.PI * 2.0d) {
            radAngle -= Math.PI * 2.0d;
        }
        while (radAngle < 0.0d) {
            radAngle += Math.PI * 2.0d;
        }
        return radAngle;
    }

    public static double radius_at_lat_lon(double lat, double lon)
    {
        lat = Math.toRadians(lat);
        lon = Math.toRadians(lon); // not used

        final double A = 6378137.0d; // equatorial radius of WGS ellipsoid, in meters
        final double B = 6356752.3d; // polar radius of WGS ellipsoid, in meters
        double r = squared(A * A * cos(lat)) + squared(B * B * sin(lat)); // numerator
        r /= squared(A * cos(lat)) + squared(B * sin(lat)); // denominator
        r = Math.sqrt(r); // square root
        return r;
    }
    
} // PolarCoordinates


