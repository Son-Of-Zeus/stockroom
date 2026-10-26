package com.example.demo.inventory;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class RedisClient {
    public Object command(String endpoint, String... arguments) {
        String[] address = endpoint.split(":", -1);
        if (address.length != 2) throw new IllegalArgumentException("Redis endpoint must use host:port.");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address[0], Integer.parseInt(address[1])), 700);
            socket.setSoTimeout(700);
            socket.setTcpNoDelay(true);
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            output.write(("*" + arguments.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String argument : arguments) {
                byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.write(new byte[]{13, 10});
            }
            output.flush();
            return read(new BufferedInputStream(socket.getInputStream()), 0);
        } catch (IOException | IllegalArgumentException e) {
            throw new ApiException(503, "Redis service is unavailable.");
        }
    }

    static Object read(InputStream input, int depth) throws IOException {
        if (depth > 8) throw new IOException("Invalid Redis response.");
        int type = input.read();
        if (type == -1) throw new EOFException();
        String line = line(input);
        return switch (type) {
            case '+' -> line;
            case '-' -> throw new IOException("Redis command failed.");
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length == -1) yield null;
                if (length < 0 || length > 1048576) throw new IOException("Invalid Redis response length.");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length || input.read() != 13 || input.read() != 10) throw new EOFException();
                yield new String(bytes, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int length = Integer.parseInt(line);
                if (length < 0 || length > 1024) throw new IOException("Invalid Redis array length.");
                List<Object> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) values.add(read(input, depth + 1));
                yield values;
            }
            default -> throw new IOException("Invalid Redis response type.");
        };
    }

    private static String line(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            int character = input.read();
            if (character == -1) throw new EOFException();
            if (character == 13) {
                if (input.read() != 10) throw new IOException("Invalid Redis line.");
                return text.toString();
            }
            text.append((char) character);
        }
        throw new IOException("Redis line is too long.");
    }

    public boolean healthy(String endpoint) {
        try {
            return "PONG".equals(command(endpoint, "PING"));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
