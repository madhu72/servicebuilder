package com.mftlabs.autobuild;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;
public class B2bApiMgr {

    private void logEvent(String level, String message) {
        Logger logger = Logger.getLogger(B2bApiMgr.class.getName());
        if (level.equalsIgnoreCase("debug")) {
            logger.fine(message);
        } else if (level.equalsIgnoreCase("info")) {
            logger.info(message);
        } else if (level.equalsIgnoreCase("warn")) {
            logger.warning(message);
        } else if (level.equalsIgnoreCase("error")) {
            logger.severe(message);
        }
    }
    private void invokeB2BApiCall(String apiHost, String apiURI, Map<String, Object> dataSFTP) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(apiHost + "/B2BAPIs/svc/" + apiURI + "/");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Convert Map to JSON string
            ObjectMapper objectMapper = new ObjectMapper();
            String jsonInputString = objectMapper.writeValueAsString(dataSFTP);

            // Send request
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            logEvent("info", "Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                logEvent("info", "API call was successful.");
            } else {
                throw new RuntimeException("API call failed with response code: " + responseCode);
            }

        } catch (Exception e) {
            logEvent("error", "Error occurred while making API call: " + e.getMessage());
            throw new RuntimeException("Error occurred while making API call: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
