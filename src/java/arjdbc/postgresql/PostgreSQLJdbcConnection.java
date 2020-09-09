/***** BEGIN LICENSE BLOCK *****
 * Copyright (c) 2012-2015 Karol Bucek <self@kares.org>
 * Copyright (c) 2006-2010 Nick Sieger <nick@nicksieger.com>
 * Copyright (c) 2006-2007 Ola Bini <ola.bini@gmail.com>
 * Copyright (c) 2008-2009 Thomas E Enebo <enebo@acm.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 ***** END LICENSE BLOCK *****/
package arjdbc.postgresql;

import arjdbc.jdbc.Callable;
import arjdbc.jdbc.DriverWrapper;
import arjdbc.postgresql.PostgreSQLResult;

import java.io.ByteArrayInputStream;
import java.lang.StringBuilder;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.joda.time.DateTimeZone;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyIO;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.javasupport.JavaUtil;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

import org.jruby.util.SafePropertyAccessor;
import org.postgresql.PGConnection;
import org.postgresql.PGStatement;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGobject;

import static arjdbc.util.StringHelper.*;

/**
 *
 * @author enebo
 */
public class PostgreSQLRubyJdbcConnection extends arjdbc.jdbc.RubyJdbcConnection {
    private static final long serialVersionUID = 7235537759545717760L;
    private static final int HSTORE_TYPE = 100000 + 1111;
    private static final Pattern doubleValuePattern = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern uuidPattern = Pattern.compile("^\\p{XDigit}{8}-(?:\\p{XDigit}{4}-){3}\\p{XDigit}{12}$");

    private static final Map<String, Integer> POSTGRES_JDBC_TYPE_FOR = new HashMap<String, Integer>(32, 1);
    static {
        POSTGRES_JDBC_TYPE_FOR.put("bit", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("bit_varying", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("box", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("circle", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("citext", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("daterange", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("hstore", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("int4range", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("int8range", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("interval", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("json", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("jsonb", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("line", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("lseg", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("ltree", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("numrange", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("path", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("point", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("polygon", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("tsrange", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("tstzrange", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("tsvector", Types.OTHER);
        POSTGRES_JDBC_TYPE_FOR.put("uuid", Types.OTHER);
    }

    public PostgreSQLRubyJdbcConnection(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
    }

    public static RubyClass createPostgreSQLJdbcConnectionClass(Ruby runtime, RubyClass jdbcConnection) {
        final RubyClass clazz = getConnectionAdapters(runtime).
            defineClassUnder("PostgreSQLJdbcConnection", jdbcConnection, ALLOCATOR);
        clazz.defineAnnotatedMethods(PostgreSQLRubyJdbcConnection.class);
        getConnectionAdapters(runtime).setConstant("PostgresJdbcConnection", clazz); // backwards-compat
        return clazz;
    }

    public static RubyClass load(final Ruby runtime) {
        RubyClass jdbcConnection = getJdbcConnection(runtime);
        RubyClass postgreSQLConnection = createPostgreSQLJdbcConnectionClass(runtime, jdbcConnection);
        PostgreSQLResult.createPostgreSQLResultClass(runtime, postgreSQLConnection);
        return postgreSQLConnection;
    }

    protected static final ObjectAllocator ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new PostgreSQLRubyJdbcConnection(runtime, klass);
        }
    };

    @Override
    protected String buildURL(final ThreadContext context, final IRubyObject url) {
        // (deprecated AR-JDBC specific url) options: disabled with adapter: postgresql
        // since it collides with AR as it likes to use the key for its own purposes :
        // e.g. config[:options] = "-c geqo=off"
        return DriverWrapper.buildURL(url, Collections.EMPTY_MAP);
    }

    @Override
    protected DriverWrapper newDriverWrapper(final ThreadContext context, final String driver) {
        DriverWrapper driverWrapper = super.newDriverWrapper(context, driver);

        final java.sql.Driver jdbcDriver = driverWrapper.getDriverInstance();
        if ( jdbcDriver.getClass().getName().startsWith("org.postgresql.") ) {
            try { // public static String getVersion()
                final String version = (String) // "PostgreSQL 9.2 JDBC4 (build 1002)"
                    jdbcDriver.getClass().getMethod("getVersion").invoke(null);
                if ( version != null && version.indexOf("JDBC3") >= 0 ) {
                    // config[:connection_alive_sql] ||= 'SELECT 1'
                    setConfigValueIfNotSet(context, "connection_alive_sql", context.runtime.newString("SELECT 1"));
                }
            }
            catch (NoSuchMethodException e) { }
            catch (SecurityException e) { }
            catch (IllegalAccessException e) { }
            catch (InvocationTargetException e) { }
        }

        return driverWrapper;
    }

    @Override
    protected final IRubyObject beginTransaction(final ThreadContext context, final Connection connection,
        final IRubyObject isolation) throws SQLException {
        // NOTE: only reversed order - just to ~ match how Rails does it :
        /* if ( connection.getAutoCommit() ) */ connection.setAutoCommit(false);
        if ( isolation != null ) setTransactionIsolation(context, connection, isolation);
        return context.nil;
    }

    // storesMixedCaseIdentifiers() return false;
    // storesLowerCaseIdentifiers() return true;
    // storesUpperCaseIdentifiers() return false;

    @Override
    protected String caseConvertIdentifierForRails(final Connection connection, final String value)
        throws SQLException {
        return value;
    }

    @Override
    protected String caseConvertIdentifierForJdbc(final Connection connection, final String value)
        throws SQLException {
        return value;
    }

    @Override
    protected String internedTypeFor(final ThreadContext context, final IRubyObject attribute) throws SQLException {
        if ( oidArray(context).isInstance(attributeType(context, attribute)) ) {
            return "array";
        }

        return super.internedTypeFor(context, attribute);
    }

    @JRubyMethod(name = "database_product")
    public IRubyObject database_product(final ThreadContext context) {
        return withConnection(context, new Callable<IRubyObject>() {
            public IRubyObject call(final Connection connection) throws SQLException {
                final DatabaseMetaData metaData = connection.getMetaData();
                return RubyString.newString(context.runtime, metaData.getDatabaseProductName() + ' ' + metaData.getDatabaseProductVersion());
            }
        });
    }

    private transient RubyClass oidArray; // PostgreSQL::OID::Array

    private RubyClass oidArray(final ThreadContext context) {
        if (oidArray != null) return oidArray;
        final RubyModule PostgreSQL = (RubyModule) getConnectionAdapters(context.runtime).getConstant("PostgreSQL");
        return oidArray = ((RubyModule) PostgreSQL.getConstantAt("OID")).getClass("Array");
    }


    @Override
    protected Connection newConnection() throws SQLException {
        final Connection connection = getConnectionFactory().newConnection();
        final PGConnection pgConnection;
        if ( connection instanceof PGConnection ) {
            pgConnection = (PGConnection) connection;
        }
        else {
            pgConnection = connection.unwrap(PGConnection.class);
        }
        pgConnection.addDataType("daterange", DateRangeType.class);
        pgConnection.addDataType("tsrange",   TsRangeType.class);
        pgConnection.addDataType("tstzrange", TstzRangeType.class);
        pgConnection.addDataType("int4range", Int4RangeType.class);
        pgConnection.addDataType("int8range", Int8RangeType.class);
        pgConnection.addDataType("numrange",  NumRangeType.class);
        return connection;
    }

    @Override
    protected IRubyObject mapExecuteResult(final ThreadContext context, final Connection connection,
                                           final ResultSet resultSet) throws SQLException{
        return PostgreSQLResult.newResult(context, resultSet, getAdapter());
    }

    /**
     * Maps a query result set into a <code>ActiveRecord</code> result.
     * @param context
     * @param connection
     * @param resultSet
     * @return <code>ActiveRecord::Result</code>
     * @throws SQLException
     */
    protected IRubyObject mapQueryResult(final ThreadContext context, final Connection connection,
                                         final ResultSet resultSet) throws SQLException {
        return ((PostgreSQLResult) mapExecuteResult(context, connection, resultSet)).toARResult(context);
    }

    @Override
    protected void setBlobParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( value instanceof RubyIO ) { // IO/File
            statement.setBinaryStream(index, ((RubyIO) value).getInStream());
        }
        else { // should be a RubyString
            final ByteList bytes = value.asString().getByteList();
            statement.setBinaryStream(index,
                    new ByteArrayInputStream(bytes.unsafeBytes(), bytes.getBegin(), bytes.getRealSize()),
                    bytes.getRealSize() // length
            );
        }
    }

    @Override // to handle infinity timestamp values
    protected void setTimestampParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( value instanceof RubyFloat ) {
            final double doubleValue = ( (RubyFloat) value ).getValue();
            if ( Double.isInfinite(doubleValue) ) {
                final Timestamp timestamp;
                if ( doubleValue < 0 ) {
                    timestamp = new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
                }
                else {
                    timestamp = new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
                }
                statement.setTimestamp( index, timestamp );
                return;
            }
        }

        super.setTimestampParameter(context, connection, statement, index, value, attribute, type);
    }

    @Override
    protected void setTimeParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {
        // to handle more fractional second precision than (default) 59.123 only
        super.setTimestampParameter(context, connection, statement, index, value, attribute, type);
    }

    @Override
    protected void setDateParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( ! "Date".equals(value.getMetaClass().getName()) && value.respondsTo("to_date") ) {
            value = value.callMethod(context, "to_date");
        }

        // NOTE: assuming Date#to_s does right ...
        statement.setDate(index, Date.valueOf(value.asString().toString()));
    }

    @Override
    protected void setObjectParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        final String columnType = attributeSQLType(context, attribute).asJavaString();
        Double[] pointValues;

        switch ( columnType ) {
            case "bit":
            case "bit_varying":
                setPGobjectParameter(statement, index, value.toString(), "bit");
                break;

            case "box":
                pointValues = parseDoubles(value);
                statement.setObject(index, new PGbox(pointValues[0], pointValues[1], pointValues[2], pointValues[3]));
                break;

            case "circle":
                pointValues = parseDoubles(value);
                statement.setObject(index, new PGcircle(pointValues[0], pointValues[1], pointValues[2]));
                break;

            case "cidr":
            case "citext":
            case "hstore":
            case "inet":
            case "ltree":
            case "macaddr":
            case "tsvector":
                setPGobjectParameter(statement, index, value, columnType);
                break;

            case "interval":
                statement.setObject(index, new PGInterval(value.toString()));
                break;

            case "json":
            case "jsonb":
                setJsonParameter(context, statement, index, value, columnType);
                break;

            case "line":
                pointValues = parseDoubles(value);
                if ( pointValues.length == 3 ) {
                    statement.setObject(index, new PGline(pointValues[0], pointValues[1], pointValues[2]));
                } else {
                    statement.setObject(index, new PGline(pointValues[0], pointValues[1], pointValues[2], pointValues[3]));
                }
                break;

            case "lseg":
                pointValues = parseDoubles(value);
                statement.setObject(index, new PGlseg(pointValues[0], pointValues[1], pointValues[2], pointValues[3]));
                break;

            case "path":
                // If the value starts with "[" it is open, otherwise postgres treats it as a closed path
                statement.setObject(index, new PGpath((PGpoint[]) convertToPoints(parseDoubles(value)), value.toString().startsWith("[")));
                break;

            case "point":
                pointValues = parseDoubles(value);
                statement.setObject(index, new PGpoint(pointValues[0], pointValues[1]));
                break;

            case "polygon":
                statement.setObject(index, new PGpolygon((PGpoint[]) convertToPoints(parseDoubles(value))));
                break;

            case "uuid":
                setUUIDParameter(statement, index, value);
                break;

            default:
                if ( columnType.endsWith("range") ) {
                    setRangeParameter(context, statement, index, value, columnType);
                }
                else {
                    super.setObjectParameter(context, connection, statement, index, value, attribute, type);
                }
        }
    }

    // The tests won't start if this returns PGpoint[]
    // it fails with a runtime error: "NativeException: java.lang.reflect.InvocationTargetException: [Lorg/postgresql/geometric/PGpoint"
    private Object[] convertToPoints(Double[] values) throws SQLException {
        PGpoint[] points = new PGpoint[values.length / 2];

        for ( int i = 0; i < values.length; i += 2 ) {
            points[i / 2] = new PGpoint(values[i], values[i + 1]);
        }

        return points;
    }

    private Double[] parseDoubles(IRubyObject value) {
        Matcher matches = doubleValuePattern.matcher(value.toString());
        ArrayList<Double> doubles = new ArrayList<Double>(4); // Paths and polygons may be larger but this covers points/circles/boxes/line segments

        while ( matches.find() ) {
            doubles.add(new Double(matches.group()));
        }

        return doubles.toArray(new Double[doubles.size()]);
    }

    private void setJsonParameter(final ThreadContext context,
        final PreparedStatement statement, final int index,
        final IRubyObject value, final String columnType) throws SQLException {

        final PGobject pgJson = new PGobject();
        pgJson.setType(columnType);
        pgJson.setValue(value.toString());
        statement.setObject(index, pgJson);
    }

    private void setPGobjectParameter(final PreparedStatement statement, final int index,
        final Object value, final String columnType) throws SQLException {

        final PGobject param = new PGobject();
        param.setType(columnType);
        param.setValue(value.toString());
        statement.setObject(index, param);
    }

    private void setRangeParameter(final ThreadContext context,
        final PreparedStatement statement, final int index,
        final IRubyObject value, final String columnType) throws SQLException {

        final String rangeValue = value.toString();
        final Object pgRange;

        switch ( columnType ) {
            case "daterange":
                pgRange = new DateRangeType(rangeValue);
                break;
            case "tsrange":
                pgRange = new TsRangeType(rangeValue);
                break;
            case "tstzrange":
                pgRange = new TstzRangeType(rangeValue);
                break;
            case "int4range":
                pgRange = new Int4RangeType(rangeValue);
                break;
            case "int8range":
                pgRange = new Int8RangeType(rangeValue);
                break;
            default:
                pgRange = new NumRangeType(rangeValue);
        }

        statement.setObject(index, pgRange);
    }

    @Override
    protected void setStringParameter(final ThreadContext context,
        final Connection connection, final PreparedStatement statement,
        final int index, final IRubyObject value,
        final IRubyObject attribute, final int type) throws SQLException {

        if ( attributeSQLType(context, attribute).isNil() ) {
            /*
                We have to check for a uuid here because in some cases
                (for example,  when doing "exists?" checks, or with legacy binds)
                ActiveRecord doesn't send us the actual type of the attribute
                and Postgres won't compare a uuid column with a string
            */
            final String uuid = value.toString();

            // Checking the length so we don't have the overhead of the regex unless it "looks" like a UUID
            if ( uuid.length() == 36 && uuidPattern.matcher(uuid).matches() ) {
                setUUIDParameter(statement, index, value);
                return;
            }
        }

        super.setStringParameter(context, connection, statement, index, value, attribute, type);
    }

    private void setUUIDParameter(final PreparedStatement statement,
        final int index, final IRubyObject value) throws SQLException {

        statement.setObject(index, UUID.fromString(value.toString()));
    }

    @Override
    protected Integer jdbcTypeFor(final String type) {

        Integer typeValue = POSTGRES_JDBC_TYPE_FOR.get(type);

        if ( typeValue != null ) {
            return typeValue;
        }

        return super.jdbcTypeFor(type);
    }

    @Override
    protected TableName extractTableName(
        final Connection connection, String catalog, String schema,
        final String tableName) throws IllegalArgumentException, SQLException {
        // The postgres JDBC driver will default to searching every schema if no
        // schema search path is given.  Default to the 'public' schema instead:
        if ( schema == null ) schema = "public";
        return super.extractTableName(connection, catalog, schema, tableName);
    }
    @JRubyMethod // lazy attempt for PG compatibility
    public IRubyObject transaction_status(final ThreadContext context) {
        final Connection connection = getConnection(false);
        if ( connection == null ) {
            return context.getRuntime().newFixnum(4); // PQTRANS_UNKNOWN(4)
        }
        try {
            final int txState = connection.unwrap(BaseConnection.class).getTransactionState();
            final int pgState;
            switch ( txState ) {
                case 0: // TRANSACTION_IDLE = 0
                    pgState = 0; break; // PQTRANS_IDLE(0)
                case 1: // TRANSACTION_OPEN = 1
                    pgState = 2; break; // PQTRANS_ACTIVE(1)
                case 2: // TRANSACTION_FAILED = 2
                    pgState = 3; break; // PQTRANS_INERROR(3)
                // NOTE: PQTRANS_INTRANS(2) not covered !
                default: pgState = 4; // PQTRANS_UNKNOWN(4)
            }
            return context.getRuntime().newFixnum(pgState);
        }
        catch (SQLException e) { // unwrap failed
            return context.getRuntime().getNil();
        }
    }

    // NOTE: do not use PG classes in the API so that loading is delayed !
    private String formatInterval(final Object object) {
        final PGInterval interval = (PGInterval) object;
        if ( rawIntervalType ) return interval.getValue();

        final StringBuilder str = new StringBuilder(32);

        final int years = interval.getYears();
        if ( years != 0 ) str.append(years).append(" years ");
        final int months = interval.getMonths();
        if ( months != 0 ) str.append(months).append(" months ");
        final int days = interval.getDays();
        if ( days != 0 ) str.append(days).append(" days ");
        final int hours = interval.getHours();
        final int mins = interval.getMinutes();
        final int secs = (int) interval.getSeconds();
        if ( hours != 0 || mins != 0 || secs != 0 ) { // xx:yy:zz if not all 00
            if ( hours < 10 ) str.append('0');
            str.append(hours).append(':');
            if ( mins < 10 ) str.append('0');
            str.append(mins).append(':');
            if ( secs < 10 ) str.append('0');
            str.append(secs);
        }
        else {
            if ( str.length() > 1 ) str.deleteCharAt( str.length() - 1 ); // " " at the end
        }

        return str.toString();
    }

    protected static Boolean rawArrayType;
    static {
        final String arrayRaw = System.getProperty("arjdbc.postgresql.array.raw");
        if ( arrayRaw != null ) rawArrayType = Boolean.parseBoolean(arrayRaw);
    }

    @JRubyMethod(name = "raw_array_type?", meta = true)
    public static IRubyObject useRawArrayType(final ThreadContext context, final IRubyObject self) {
        if ( rawArrayType == null ) return context.getRuntime().getNil();
        return context.getRuntime().newBoolean(rawArrayType);
    }

    @JRubyMethod(name = "raw_array_type=", meta = true)
    public static IRubyObject setRawArrayType(final IRubyObject self, final IRubyObject value) {
        if ( value instanceof RubyBoolean ) {
            rawArrayType = ((RubyBoolean) value).isTrue() ? Boolean.TRUE : Boolean.FALSE;
        }
        else {
            rawArrayType = value.isNil() ? null : Boolean.TRUE;
        }
        return value;
    }

    protected static Boolean rawHstoreType;
    static {
        final String hstoreRaw = System.getProperty("arjdbc.postgresql.hstore.raw");
        if ( hstoreRaw != null ) rawHstoreType = Boolean.parseBoolean(hstoreRaw);
    }

    @JRubyMethod(name = "raw_hstore_type?", meta = true)
    public static IRubyObject useRawHstoreType(final ThreadContext context, final IRubyObject self) {
        if ( rawHstoreType == null ) return context.getRuntime().getNil();
        return context.getRuntime().newBoolean(rawHstoreType);
    }

    @JRubyMethod(name = "raw_hstore_type=", meta = true)
    public static IRubyObject setRawHstoreType(final IRubyObject self, final IRubyObject value) {
        if ( value instanceof RubyBoolean ) {
            rawHstoreType = ((RubyBoolean) value).isTrue() ? Boolean.TRUE : Boolean.FALSE;
        }
        else {
            rawHstoreType = value.isNil() ? null : Boolean.TRUE;
        }
        return value;
    }

    // whether to use "raw" interval values off by default - due native adapter compatibilty :
    // RAW values :
    // - 2 years 0 mons 0 days 0 hours 3 mins 0.00 secs
    // - -1 years 0 mons -2 days 0 hours 0 mins 0.00 secs
    // Rails style :
    // - 2 years 00:03:00
    // - -1 years -2 days
    protected static boolean rawIntervalType = Boolean.getBoolean("arjdbc.postgresql.iterval.raw");

    @JRubyMethod(name = "raw_interval_type?", meta = true)
    public static IRubyObject useRawIntervalType(final ThreadContext context, final IRubyObject self) {
        return context.getRuntime().newBoolean(rawIntervalType);
    }

    @JRubyMethod(name = "raw_interval_type=", meta = true)
    public static IRubyObject setRawIntervalType(final IRubyObject self, final IRubyObject value) {
        if ( value instanceof RubyBoolean ) {
            rawIntervalType = ((RubyBoolean) value).isTrue();
        }
        else {
            rawIntervalType = ! value.isNil();
        }
        return value;
    }

    // NOTE: without these custom registered Postgre (driver) types
    // ... we can not set range parameters in prepared statements !

    public static class DateRangeType extends PGobject {

        private static final long serialVersionUID = -5378414736244196691L;

        public DateRangeType() {
            setType("daterange");
        }

        public DateRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static class TsRangeType extends PGobject {

        private static final long serialVersionUID = -2991390995527988409L;

        public TsRangeType() {
            setType("tsrange");
        }

        public TsRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static class TstzRangeType extends PGobject {

        private static final long serialVersionUID = 6492535255861743334L;

        public TstzRangeType() {
            setType("tstzrange");
        }

        public TstzRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static class Int4RangeType extends PGobject {

        private static final long serialVersionUID = 4490562039665289763L;

        public Int4RangeType() {
            setType("int4range");
        }

        public Int4RangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static class Int8RangeType extends PGobject {

        private static final long serialVersionUID = -1458706215346897102L;

        public Int8RangeType() {
            setType("int8range");
        }

        public Int8RangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

    public static class NumRangeType extends PGobject {

        private static final long serialVersionUID = 5892509252900362510L;

        public NumRangeType() {
            setType("numrange");
        }

        public NumRangeType(final String value) throws SQLException {
            this();
            setValue(value);
        }

    }

}