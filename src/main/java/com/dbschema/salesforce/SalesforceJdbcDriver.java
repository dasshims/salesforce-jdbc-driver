package com.dbschema.salesforce;


import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.h2.jdbc.JdbcConnection;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Copyright Wise Coders Gmbh. Licensed under BSD License-3: free to use,distribution forbidden. Improvements accepted only in https://bitbucket.org/dbschema/salesforce-jdbc-driver
 * When you open a connection we load internally the list of tables.
 * When you execute a query, we check which table names can be found in the query and we transfer them in an internal H2 database stored in user.home/.DbSchema
 * We also create a proxy on Statement and intercept certain commands we implement in the driver.
 * The driver can be improved, we are happy for contributions.
 */
public class SalesforceJdbcDriver implements Driver {

    private static final String JDBC_PREFIX = "jdbc:dbschema:salesforce://";
    private static final String INTERNAL_H2_LOCATION = ".DbSchema/jdbc-salesforce-cache/";
    public static final Logger LOGGER = Logger.getLogger( SalesforceJdbcDriver.class.getName() );


    static {
        try {
            DriverManager.registerDriver( new SalesforceJdbcDriver());
            LOGGER.setLevel(Level.SEVERE);
            final ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.FINEST);
            consoleHandler.setFormatter(new SimpleFormatter());

            LOGGER.setLevel(Level.FINEST);
            LOGGER.addHandler(consoleHandler);
        } catch ( SQLException ex ){
            ex.printStackTrace();
        }
    }


    @Override
    public Connection connect( String url, Properties info ) throws SQLException {
        if ( acceptsURL(JDBC_PREFIX)) {
            final Map<String,String> parameters = new HashMap<>();
            if ( info != null ){
                for ( Object key : info.keySet()){
                    parameters.put( key.toString(), info.get( key ).toString() );
                }
            }
            String data = url.substring(JDBC_PREFIX.length());
            if ( data.startsWith("?")) data = data.substring(1);
            System.out.println("Data is = " + data);
            for ( String pair: data.split("&")){
                String[] pairArray = pair.split("=");
                if( pairArray.length == 2 ){
                    System.out.println("key=" + pairArray[0].toLowerCase() + " val=" + pairArray[1] );
                    parameters.put( pairArray[0].toLowerCase(), pairArray[1]);
                }
            }
            final String userName = parameters.get("user");
            final String password = parameters.get("password");
            final String sessionId = parameters.get("sessionid");
            PartnerConnection partnerConnection;
            if ( sessionId != null ) {
                partnerConnection = getPartnerConnection(sessionId);
            } else {
                if (userName == null) throw new SQLException("Missing username. Please add it to URL as user=<value>");
                if (password == null) throw new SQLException("Missing password. Please add it to URL as password=<value>");
                partnerConnection = getPartnerConnection( userName, password );
            }
            final String h2DbName = md5Java( userName != null ? userName : sessionId );

            return getSalesforceConnection( h2DbName, partnerConnection, parameters );
        } else {
            throw new SQLException("Incorrect URL. Expected jdbc:dbschema:dbf:<folderPath>");
        }
    }

    private SalesforceConnection getSalesforceConnection(String dbId, PartnerConnection partnerConnection, Map<String,String> parameters ) throws SQLException {
        final URI h2DatabasePath = getH2DatabasePath( dbId );
        final String h2JdbcUrl = "jdbc:h2:" + h2DatabasePath.toASCIIString() + ";database_to_upper=false";
        LOGGER.log(Level.INFO, "Create H2 database '" + h2JdbcUrl + "'");
        return new SalesforceConnection( dbId, (JdbcConnection)(new org.h2.Driver().connect( h2JdbcUrl, new Properties() )), partnerConnection,  parameters );
    }

    private PartnerConnection getPartnerConnection(String sessionId ) throws SQLException {
        try {
            final ConnectorConfig config = new ConnectorConfig();
            config.setSessionId( sessionId );
            return createConnection(config);
        } catch ( Throwable cause ){
            throw new SQLException(cause);
        }
    }
    private PartnerConnection getPartnerConnection(String user, String password ) throws SQLException {
        try {
            final ConnectorConfig config = new ConnectorConfig();
            config.setUsername(user);
            config.setPassword(password);
            return createConnection(config);
        } catch ( Throwable cause ){
            throw new SQLException(cause);
        }
    }

    private PartnerConnection createConnection(ConnectorConfig config) throws ConnectionException {
        try {
            config.setAuthEndpoint(String.format("https://%s/services/Soap/u/%s", DEFAULT_LOGIN_DOMAIN, DEFAULT_API_VERSION));
            return Connector.newConnection(config);
        } catch (ConnectionException ce) {
            config.setAuthEndpoint(String.format("https://%s/services/Soap/u/%s", SANDBOX_LOGIN_DOMAIN, DEFAULT_API_VERSION));
            return Connector.newConnection(config);
        }
    }


    private static final String DEFAULT_LOGIN_DOMAIN = "login.salesforce.com";
    private static final String SANDBOX_LOGIN_DOMAIN = "test.salesforce.com";
    private static final long CONNECTION_TIMEOUT = TimeUnit.SECONDS.toMillis(10);
    private static final long READ_TIMEOUT = TimeUnit.SECONDS.toMillis(30);
    private static final String DEFAULT_API_VERSION = "43.0";


    private URI getH2DatabasePath(String path ){
        final URI h2Home = new File( System.getProperty("user.home")).toURI().resolve(INTERNAL_H2_LOCATION);
        final File h2File = new File(h2Home);
        if ( !h2File.exists()) {
            h2File.mkdirs();
        }
        return h2Home.resolve( path );
    }

    @Override
    public boolean acceptsURL(String url) {
        return url.startsWith(JDBC_PREFIX);
    }

    class ExtendedDriverPropertyInfo extends DriverPropertyInfo {
        ExtendedDriverPropertyInfo( String name, String value, String[] choices, String description ){
            super( name, value);
            this.description = description;
            this.choices = choices;
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo[] result = new DriverPropertyInfo[1];
        result[0] = new ExtendedDriverPropertyInfo("log", "true", new String[]{"true", "false"}, "Activate driver INFO logging");
        return result;
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return true;
    }

    @Override
    public Logger getParentLogger() {
        return null;
    }

    private static String md5Java(String message){
        String digest = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(message.getBytes(StandardCharsets.UTF_8));

            //converting byte array to Hexadecimal String
            StringBuilder sb = new StringBuilder(2*hash.length);
            for(byte b : hash){
                sb.append(String.format("%02x", b&0xff));
            }

            digest = sb.toString();

        } catch ( NoSuchAlgorithmException ex) {
            Logger.getLogger(SalesforceJdbcDriver.class.getName()).log(Level.SEVERE, null, ex);
        }
        return digest;
    }





}