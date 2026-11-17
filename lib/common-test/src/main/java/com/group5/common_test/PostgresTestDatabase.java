package com.group5.common_test;

import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import java.util.UUID;

/** Optional native PG18 creates an isolated database; Docker remains the default. */
public final class PostgresTestDatabase extends PostgreSQLContainer {
    private final String admin=System.getProperty("nexus.test.jdbc-url",System.getenv("TEST_POSTGRES_ADMIN_URL"));
    private String database,url;
    public PostgresTestDatabase(String image) { super(image); }
    @Override public synchronized void start() {
        if(admin==null || admin.isBlank()) {super.start();return;}
        if(url!=null)return;
        database="nexus_native_"+UUID.randomUUID().toString().replace("-","");
        execute("CREATE DATABASE "+database);
        String base=admin.split("\\?",2)[0];url=base.substring(0,base.lastIndexOf('/')+1)+database;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }
    @Override public String getJdbcUrl() {return nativeMode()?url:super.getJdbcUrl();}
    @Override public String getUsername() {return nativeMode()?System.getProperty("nexus.test.jdbc-user",System.getenv().getOrDefault("TEST_POSTGRES_USERNAME","postgres")):super.getUsername();}
    @Override public String getPassword() {return nativeMode()?System.getProperty("nexus.test.jdbc-password",System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","")):super.getPassword();}
    @Override public synchronized void stop() {
        if(!nativeMode()){super.stop();return;}
        if(url==null)return;execute("DROP DATABASE IF EXISTS "+database+" WITH (FORCE)");url=null;
    }
    private boolean nativeMode(){return admin!=null && !admin.isBlank();}
    private void execute(String sql){try(var c=DriverManager.getConnection(admin,getUsername(),getPassword());var s=c.createStatement()){s.execute(sql);}catch(java.sql.SQLException error){throw new IllegalStateException("Isolated PostgreSQL fixture failed",error);}}
}
