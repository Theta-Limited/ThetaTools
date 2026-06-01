// DumpHsqlDB.java
// dump an hsqldb using Core's bundled hsqldb code
// chatgpt written

// copy latest core jar file to ThetaTools dir
// javac DumpHsqlDB.java
// java -cp .:./core-1.1.0-SNAPSHOT.jar DumpHsqlDB /tmp/unitTestDB openathenacore ""

import java.sql.*;

public class DumpHsqlDB
{
    public static void main(String[] args)
    {
        if (args.length < 1 || args.length > 3) {
            usage();
            System.exit(2);
        }

        String dbPath = args[0];
        String user = args.length >= 2 ? args[1] : "SA";
        String password = args.length >= 3 ? args[2] : "";

        String url = dbPath.startsWith("jdbc:")
                ? dbPath
                : "jdbc:hsqldb:file:" + dbPath;

        try {
            Class.forName("org.hsqldb.jdbc.JDBCDriver");

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                dumpDatabaseInfo(conn);
                dumpDatabase(conn);
            }

        } catch (Exception e) {
            System.err.println("Failed to dump HSQLDB database: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void dumpDatabaseInfo(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();

        System.out.println("Database:");
        System.out.println("  Product: " + meta.getDatabaseProductName());
        System.out.println("  Version: " + meta.getDatabaseProductVersion());
        System.out.println("  Driver: " + meta.getDriverName());
        System.out.println("  Driver version: " + meta.getDriverVersion());
        System.out.println("  JDBC URL: " + meta.getURL());
        System.out.println("  User: " + meta.getUserName());
        System.out.println("  Read-only: " + conn.isReadOnly());
        System.out.println("  Auto-commit: " + conn.getAutoCommit());
        System.out.println();
    }

    private static void dumpTableMetadata(Connection conn, String tableName) throws SQLException
    {
        DatabaseMetaData meta = conn.getMetaData();

        System.out.println("Columns:");
        try (ResultSet cols = meta.getColumns(null, "PUBLIC", tableName, "%")) {
            while (cols.next()) {
                String name = cols.getString("COLUMN_NAME");
                String type = cols.getString("TYPE_NAME");
                int size = cols.getInt("COLUMN_SIZE");
                int nullable = cols.getInt("NULLABLE");

                System.out.println("  " + name
                                   + " " + type
                                   + "(" + size + ")"
                                   + (nullable == DatabaseMetaData.columnNoNulls ? " NOT NULL" : ""));
            }
        }

        System.out.println("Primary keys:");
        try (ResultSet pk = meta.getPrimaryKeys(null, "PUBLIC", tableName)) {
            while (pk.next()) {
                System.out.println("  " + pk.getString("COLUMN_NAME"));
            }
        }

        System.out.println("Indexes:");
        try (ResultSet idx = meta.getIndexInfo(null, "PUBLIC", tableName, false, false)) {
            while (idx.next()) {
                String indexName = idx.getString("INDEX_NAME");
                String columnName = idx.getString("COLUMN_NAME");
                boolean nonUnique = idx.getBoolean("NON_UNIQUE");

                if (indexName != null && columnName != null) {
                    System.out.println("  " + indexName
                                       + " on " + columnName
                                       + (nonUnique ? "" : " UNIQUE"));
                }
            }
        }

        System.out.println();
    }

    private static void dumpDatabase(Connection conn) throws SQLException
    {
        DatabaseMetaData meta = conn.getMetaData();

        try (ResultSet tables = meta.getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");

                System.out.println();
                System.out.println("============================================================");
                System.out.println("TABLE: " + tableName);
                System.out.println("============================================================");

                dumpTableMetadata(conn, tableName);

                dumpTable(conn, tableName);
            }
        }
    }

    private static void dumpTable(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT * FROM " + quoteIdentifier(tableName);

        try (
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    System.out.print(" | ");
                }
                System.out.print(md.getColumnName(i));
            }
            System.out.println();

            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    System.out.print("-+-");
                }
                System.out.print("-".repeat(Math.max(3, md.getColumnName(i).length())));
            }
            System.out.println();

            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) {
                        System.out.print(" | ");
                    }

                    Object val = rs.getObject(i);
                    System.out.print(formatValueCommon(val));
                }
                System.out.println();
            }
        }
    }

    private static String formatValueCommon(Object val) {
        if (val == null) {
            return "NULL";
        }

        try {
            if (val instanceof Clob clob) {
                return formatClob(clob);
            }

            if (val instanceof Blob blob) {
                return formatBlob(blob);
            }

            if (val instanceof byte[] bytes) {
                return formatBytes(bytes);
            }

            if (val instanceof java.sql.Date
                || val instanceof java.sql.Time
                || val instanceof java.sql.Timestamp) {
                return val.toString();
            }

            if (val instanceof Boolean
                || val instanceof Number) {
                return val.toString();
            }

            return String.valueOf(val);

        } catch (SQLException e) {
            return "[failed to format value: " + e + "]";
        }
    }

    private static String formatClob(Clob clob) throws SQLException {
        long len = clob.length();
        int maxChars = 10_000;

        String s = clob.getSubString(1, (int) Math.min(len, maxChars));

        if (len > maxChars) {
            return s + "... [CLOB truncated, length=" + len + "]";
        }

        return s;
    }

    private static String formatBlob(Blob blob) throws SQLException {
        long len = blob.length();
        int maxBytes = 64;

        byte[] bytes = blob.getBytes(1, (int) Math.min(len, maxBytes));
        String s = formatBytes(bytes);

        if (len > maxBytes) {
            return s + "... [BLOB truncated, length=" + len + "]";
        }

        return s;
    }

    private static String formatBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("0x");

        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private static String formatValueClob(Object val)
    {
        if (val == null) {
            return "NULL";
        }

        try {
            if (val instanceof Clob clob) {
                long len = clob.length();
                String s = clob.getSubString(1, (int) Math.min(len, 10000));

                if (len > 10000) {
                    return s + "... [truncated, length=" + len + "]";
                }

                return s;
            }
        } catch (SQLException e) {
            return "[failed to read CLOB: " + e + "]";
        }

        return String.valueOf(val);
    }

    private static String formatValue(Object val) {
        if (val == null) {
            return "NULL";
        }

        String s = String.valueOf(val);

        // Keep output readable for long JSON/CLOB fields.
        if (s.length() > 1000) {
            return s.substring(0, 1000) + "... [truncated, length=" + s.length() + "]";
        }

        return s;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  java DumpHsqlDb <db-path-or-jdbc-url> [user] [password]");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java DumpHsqlDb /tmp/unitTestDB");
        System.err.println("  java DumpHsqlDb jdbc:hsqldb:file:/tmp/unitTestDB SA \"\"");
    }
}
