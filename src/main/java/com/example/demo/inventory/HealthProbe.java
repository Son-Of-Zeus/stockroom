package com.example.demo.inventory;

import java.net.HttpURLConnection;
import java.net.URI;

public final class HealthProbe {
    private HealthProbe() {}

    public static void main(String[] args) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:8080/health").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(4000);
            int status = connection.getResponseCode();
            connection.disconnect();
            if (status != 200) System.exit(1);
        } catch (Exception error) {
            System.exit(1);
        }
    }
}
