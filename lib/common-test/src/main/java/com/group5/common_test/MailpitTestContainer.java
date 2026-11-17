package com.group5.common_test;

import org.testcontainers.containers.GenericContainer;
import java.net.URI;

/** A dedicated native Mailpit may be supplied; test messages are cleared by the fixture. */
public final class MailpitTestContainer extends GenericContainer<MailpitTestContainer> {
    private final String http=System.getenv("TEST_MAILPIT_HTTP_URL");
    public MailpitTestContainer(String image){super(image);}
    @Override public void start(){if(http==null)super.start();}
    @Override public void stop(){if(http==null)super.stop();}
    @Override public String getHost(){return http==null?super.getHost():URI.create(http).getHost();}
    @Override public Integer getMappedPort(int port){
        if(http==null)return super.getMappedPort(port);
        if(port==8025)return URI.create(http).getPort();
        if(port==1025)return Integer.parseInt(System.getenv("TEST_MAILPIT_SMTP_PORT"));
        throw new IllegalArgumentException("Unknown Mailpit port");
    }
}
