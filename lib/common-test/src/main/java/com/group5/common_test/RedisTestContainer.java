package com.group5.common_test;

import org.testcontainers.containers.GenericContainer;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.TimeUnit;

/** Optional local redis-server process, still exercising the real Redis protocol. */
public final class RedisTestContainer extends GenericContainer<RedisTestContainer> {
    private final String binary=System.getProperty("nexus.test.redis-binary");
    private Process process;
    private Integer port;
    public RedisTestContainer(String image){super(image);}
    @Override public synchronized void start(){
        if(binary==null){super.start();return;}
        if(process!=null && process.isAlive())return;
        try {
            if(port==null)try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
            process=new ProcessBuilder(binary,"--port",port.toString(),"--bind","127.0.0.1","--save","","--appendonly","no")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<end){
                if(!process.isAlive())throw new IOException("redis-server exited");
                try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),100);return;}
                catch(IOException retry){Thread.sleep(25);}
            }
            throw new IOException("redis-server did not start");
        }catch(IOException failure){throw new IllegalStateException("Native Redis fixture failed",failure);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }
    @Override public synchronized void stop(){
        if(binary==null){super.stop();return;}
        if(process!=null){process.destroy();try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}process=null;}
    }
    @Override public String getHost(){return binary==null?super.getHost():"127.0.0.1";}
    @Override public Integer getMappedPort(int original){return binary==null?super.getMappedPort(original):port;}
    public boolean isRunning(){return binary==null?super.isRunning():process!=null && process.isAlive();}
}
