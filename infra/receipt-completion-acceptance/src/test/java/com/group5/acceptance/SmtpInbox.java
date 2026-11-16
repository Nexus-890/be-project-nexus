package com.group5.acceptance;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TCP SMTP stand-in: production JavaMail sends real MIME bytes; only the provider is replaced. */
public final class SmtpInbox implements AutoCloseable {
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<MimeMessage> received = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public SmtpInbox() {
        try { listener = new ServerSocket(0, 20, InetAddress.getLoopbackAddress()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        workers.submit(() -> {
            while (!closed) {
                try { Socket socket = listener.accept(); workers.submit(() -> receive(socket)); }
                catch (java.io.IOException failure) { if (!closed) throw new IllegalStateException(failure); }
            }
        });
    }

    public int port() { return listener.getLocalPort(); }
    public List<MimeMessage> messages() { return List.copyOf(received); }

    private void receive(Socket socket) {
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
            socket.setSoTimeout(10000);
            reply(writer, "220 localhost receipt acceptance SMTP");
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.toUpperCase(java.util.Locale.ROOT);
                if (command.startsWith("EHLO") || command.startsWith("HELO") || command.startsWith("MAIL FROM:")
                        || command.startsWith("RCPT TO:") || command.equals("RSET") || command.equals("NOOP")) {
                    reply(writer, "250 OK");
                } else if (command.equals("DATA")) {
                    reply(writer, "354 End with dot");
                    var bytes = new StringBuilder();
                    while ((line = reader.readLine()) != null && !line.equals(".")) {
                        bytes.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    }
                    received.add(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes.toString().getBytes(StandardCharsets.UTF_8))));
                    reply(writer, "250 Message accepted");
                } else if (command.equals("QUIT")) { reply(writer, "221 Bye"); break; }
                else reply(writer, "502 Command unsupported");
            }
        } catch (Exception failure) { if (!closed) throw new IllegalStateException("SMTP stand-in failed", failure); }
    }

    private static void reply(PrintWriter writer, String line) { writer.print(line + "\r\n"); writer.flush(); }
    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (java.io.IOException ignored) { }
        workers.close();
    }
}
