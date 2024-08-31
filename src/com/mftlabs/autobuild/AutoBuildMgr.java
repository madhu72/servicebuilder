package com.mftlabs.autobuild;
//import com.fasterxml.jackson.databind.JsonMappingException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class AutoBuildMgr {

    private String configFile;
    private String templatesDir;
    private boolean debug = false;
    private AutoBuildConfig configMgr;

    private Connection connectionADC;
    private Connection connectionWDC;
    private String snADC = "snADC";
    private String snWDC = "snWDC";
    private String dsnADC = "dsnADC";
    private String dsnWDC = "dsnWDC";

    private static final Logger logger = Logger.getLogger(AutoBuildMgr.class.getName());

    static {
        try {
            // Set up a file handler with log rotation
            FileHandler fileHandler = new FileHandler("autobuild_service.log", 5 * 1024 * 1024, 10, true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);

            // Optionally, you can set a different logging level
            logger.setLevel(Level.ALL);

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to initialize log file handler", e);
        }
    }

    public static void main(String args[]) {
        AutoBuildMgr mgr = new AutoBuildMgr("PVS",null);
        mgr.isDebug();
        if (mgr.debug) {
            mgr.logEvent("info","Debug Enabled");
        } else {
            mgr.logEvent("info","Debug Disabled");
        }
    }
    public AutoBuildMgr(String env, Object manager) {
        this.configFile = System.getenv("PVS_AUTOBUILD_CONFIG");
        if (env.equalsIgnoreCase("PRD")) {
            this.configFile = System.getenv("PRD_AUTOBUILD_CONFIG");
        }
        this.configMgr = AutoBuildConfig.getInstance(this.configFile);
        String sfgHome = System.getProperty("AMF_SFG_HOME");
        this.templatesDir = sfgHome + "/templates";
        isDebug();
    }

    private void isDebug() {
        if (configMgr.getConfig().getProperty("MISC.DEBUG").equalsIgnoreCase("TRUE")) {
            this.debug = true;
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


    private Map<String, Object> loadJsonFromFile(String filePath) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // Reads the JSON file and converts it into a Map
            return objectMapper.readValue(new File(filePath), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            // This catches JsonMappingException, JsonParseException, and other IOExceptions
            throw new RuntimeException("Error occurred while loading JSON from file: " + e.getMessage(), e);
        }
    }

    public String buildSFTPProfile(Map<String, String> row, String khkKeyName) {
        try {
            Properties config = this.configMgr.getConfig();
            Map<String, Object> dataSFTP = loadJsonFromFile(this.templatesDir + "/SSHProfile.json");

            String keyPass = row.get("SFTPKey").isEmpty() ? "pass" : "key";
            if (!row.get("SFTPKey").isEmpty()) {
                dataSFTP.put("userIdentityKey", row.get("SFTPKey"));
                dataSFTP.put("preferredAuthenticationType", "PUBLIC_KEY");
            }
            dataSFTP.put("profileName", "ESI_" + row.get("SFTPHost") + "_" + row.get("SFTPPort") + "_" + row.get("SFTPUser") + "_" + keyPass);
            dataSFTP.put("remoteHost", row.get("SFTPHost"));
            dataSFTP.put("remotePort", row.get("SFTPPort"));
            dataSFTP.put("remoteUser", row.get("SFTPUser"));
            dataSFTP.put("sshPassword", row.get("SFTPPass"));

            // Handle knownHostKeys
            List<Map<String, String>> knownHostKeys = (List<Map<String, String>>) dataSFTP.get("knownHostKeys");
            if (knownHostKeys != null && !knownHostKeys.isEmpty()) {
                // Assuming you want to modify the first known host key entry
                Map<String, String> firstKey = knownHostKeys.get(0);
                firstKey.put("name", khkKeyName);
            }

            if (this.debug) {
                logEvent("debug", "Create SFTP Profile");
                logEvent("debug", dataSFTP.toString());
            }

            logEvent("info", "Creating SFTP Profile: " + dataSFTP.get("profileName") + "...");
            Thread.sleep(1000);
            try {
                invokeB2BApiCall(config.getProperty("B2B.urlADC"), "sshremoteprofiles", dataSFTP);
            } catch (Exception e1) {
                throw new RuntimeException("Error occurred while invoking api call in ADC: " + e1.getMessage());
            }
            try {
                invokeB2BApiCall(config.getProperty("B2B.urlWDC"), "sshremoteprofiles", dataSFTP);
            } catch (Exception e2) {
                throw new RuntimeException("Error occurred while invoking api call in WDC: " + e2.getMessage());
            }
            String newFilename = "/opt/ZOO/amf/lib/amf_temp/" + row.get("Direction") + "_" + row.get("Username") + "_" + row.get("AppUser") + "_" + row.get("ECSR") + "_" + "SftpProfile";
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(newFilename))) {
                writer.write((String) dataSFTP.get("profileName"));
            }
            return (String) dataSFTP.get("profileName");
        } catch (Exception e) {
            throw new RuntimeException("Error occurred while building sftp profile: " + e.getMessage());
        }
    }


    public void buildRoutingChannel(Map<String, String> row) {
        Properties config = this.configMgr.getConfig();
        try {
            Map<String, Object> dataRC = new HashMap<>();
            // Load JSON data into dataRC map from the RC.json template
            // Assuming dataRC is populated with JSON data

            dataRC.put("producer", row.get("Username"));

            if (this.debug) {
                logEvent("debug", "Building Routing Channel");
                logEvent("debug", dataRC.toString());
            }

            logEvent("info", "Creating RC for " + row.get("Username") + "...");
            Thread.sleep(1000);
            invokeB2BApiCall(config.getProperty("B2B.urlADC"), "routingchannels", dataRC);
            invokeB2BApiCall(config.getProperty("B2B.urlWDC"), "routingchannels", dataRC);
        } catch (Exception e) {
            throw new RuntimeException("Error occurred while building routing channel: " + e.getMessage());
        }
    }


    public void createOktaProfile(Map<String, String> row) {
        Properties config = this.configMgr.getConfig();
        try {
            Map<String, Object> dataOKTA = new HashMap<>();
            String jsonFile;
            if (!row.get("SSHKey").isEmpty()) {
                jsonFile = this.templatesDir + "/OKTASFTP-Key-" + config.getProperty("OKTA.tmplSuffix") + ".json";
            } else {
                jsonFile = this.templatesDir + "/OKTASFTP-PW-" + config.getProperty("OKTA.tmplSuffix") + ".json";
            }
            // Load JSON data into dataOKTA map from the template file
            // Assuming dataOKTA is populated with JSON data

            if (!row.get("SSHKey").isEmpty()) {
                logEvent("info", "SSHKey exists in row.");
                if (this.debug) {
                    logEvent("debug", dataOKTA.toString());
                }

                List<String> keysArray = Arrays.asList(row.get("SSHKey").split(";"));

                if (this.debug) {
                    logEvent("debug", "Building Okta Profile");
                    logEvent("debug", "Array length: " + keysArray.size());
                    logEvent("debug", "Keys: " + keysArray);
                }

                for (int i = 0; i < keysArray.size(); i++) {
                    if (this.debug) {
                        logEvent("debug", i + ", " + keysArray.get(i));
                    }
                    // Assuming "sshKeys" is a List in dataOKTA
                    ((List<String>) dataOKTA.get("profile.sshKeys")).add(keysArray.get(i));
                }
            }

            dataOKTA.put("profile.firstName", row.get("Username"));
            dataOKTA.put("profile.lastName", row.get("Username"));
            dataOKTA.put("profile.email", row.get("Email"));
            dataOKTA.put("profile.login", row.get("Username"));

            if (this.debug) {
                logEvent("debug", "Building Okta Profile (2)");
                logEvent("debug", dataOKTA.toString());
            }

            logEvent("info", "Creating OKTA profile for " + row.get("Username") + "...");
            Thread.sleep(1000);
            boolean success = invokeOktaApiCall(row.get("Username"), dataOKTA);
            if (!success) {
                throw new RuntimeException("Failed to invoke OKTA Api Call");
            }
        } catch (Exception e) {
            throw new RuntimeException("Error occurred while creating OKTA profile: " + e.getMessage());
        }
    }
    private boolean invokeOktaApiCall(String username, Map<String, Object> dataOKTA) {
        // Implement OKTA API call using HTTP client library
        return true; // placeholder
    }
    private void logEvent(String level, String message) {
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

    private Map<String, Object> fetchDataFromUrl(String url, boolean delete) {
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(url);
            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setRequestMethod(delete ? "DELETE" : "GET");
            connection.setRequestProperty("Content-Type", "application/json");

            int responseCode = connection.getResponseCode();
            logEvent("info", "Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();

                if (!delete) {
                    // Parse JSON response
                    ObjectMapper objectMapper = new ObjectMapper();
                    return objectMapper.readValue(content.toString(), HashMap.class);
                } else {
                    return new HashMap<>(); // For DELETE, we don't expect a body
                }
            } else {
                throw new RuntimeException("API call failed with response code: " + responseCode);
            }

        } catch (Exception e) {
            logEvent("error", "Error occurred while fetching data from URL: " + e.getMessage());
            throw new RuntimeException("Error occurred while fetching data from URL: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Map<String, Object> parseJsonFromXml(String xmlResponse) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));

            document.getDocumentElement().normalize();
            return createElementMap(document.getDocumentElement());
        } catch (Exception e) {
            logEvent("error", "Error occurred while parsing XML: " + e.getMessage());
            throw new RuntimeException("Error occurred while parsing XML: " + e.getMessage());
        }
    }

    private Map<String, Object> createElementMap(Element element) {
        Map<String, Object> map = new HashMap<>();
        NodeList nodeList = element.getChildNodes();

        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element childElement = (Element) node;
                map.put(childElement.getTagName(), childElement.getTextContent());
            }
        }

        return map;
    }

    private String postXmlData(String url, String xmlData, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(url);
            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = xmlData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            logEvent("info", "Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();
                return content.toString();
            } else {
                throw new RuntimeException("API call failed with response code: " + responseCode);
            }

        } catch (Exception e) {
            logEvent("error", "Error occurred while posting XML data: " + e.getMessage());
            throw new RuntimeException("Error occurred while posting XML data: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String getSshKeyType(String base64Key) {
        byte[] decodedBytes = Base64.getDecoder().decode(base64Key);
        String keyData = new String(decodedBytes);
        return keyData.split(" ")[0];
    }


    public String transformDestFolder(String inputDestFolder) {
        String destFolder = inputDestFolder;
        if (!inputDestFolder.contains("ECSR")) {
            destFolder = inputDestFolder.replaceFirst("/?$", "") + "/#AECSR#";
        }
        if (this.debug) {
            logEvent("debug", "inputDestFolder = " + inputDestFolder);
            logEvent("debug", "destFolder = " + destFolder);
        }
        return destFolder;
    }

    public void tryInsert(String insertSql, List<Object> data, Connection connection, String sn) {
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (int i = 0; i < data.size(); i++) {
                statement.setObject(i + 1, data.get(i));
            }
            if (this.debug) {
                logEvent("debug", insertSql);
                logEvent("debug", data.toString());
            }
            statement.executeUpdate();
            connection.commit();
            logEvent("info", sn + ": Row inserted successfully.");
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            logEvent("error", "An error occurred on " + sn + " connection: " + e.getMessage());
            if (e.getMessage().contains("unique constraint") && e.getMessage().contains("violated")) {
                logEvent("warn", "Record already exists, skipping ...");
            } else {
                throw new RuntimeException("Oracle DB Try Insert, an error occurred on " + sn + " connection: " + e.getMessage());
            }
        }
    }

    public void inPushInsert(Map<String, String> row, String sftpProfile) {
        String destFolder = transformDestFolder(row.get("DestFolder"));

        if (row.get("Dataset") != null && !row.get("Dataset").isEmpty()) {
            row.put("DestFolder", row.get("Dataset"));
        }

        String insertSql = "Insert into B2BCUSTOM.CIGNA_OB_CHANNEL_ADAPTERS (PRODUCER, CONSUMER, FILE_NAME, BP_RUN_SEQ, ADAPTER, PARM1, PARM2, PARM3, PARM4, PARM9, PARM14, PARM15, PARM16, PARM20, ECSR_NUMBER, BUCS_CODE, BUCS_IDENT) VALUES (?, ?, ?, 1, 'cigna.ch.ob.SFTPDriver', 'ESI_SFTP_Client_Internal', ?, ?, ?, ?, ?, ?, ?, 'P3', ?, ?, '113')";

        List<Object> data = Arrays.asList(row.get("Username"), row.get("AppUser"), row.get("FilenamePattern"), row.get("SFTPHost"), row.get("SFTPPort"), sftpProfile, destFolder, row.get("MFOptions"), row.get("LFConversion"), row.get("Compression"), row.get("ECSR"), row.get("AppUser"));

        tryInsert(insertSql, data, this.connectionADC, this.snADC);
        tryInsert(insertSql, data, this.connectionWDC, this.snWDC);
    }

    public void obPullInsert(Map<String, String> row) {
        String dFolder = row.get("DestFolder").isEmpty() ? "Inbox" : row.get("DestFolder").split("/")[1];
        String fName = row.get("DestFolder").isEmpty() ? "#AECSR#" : row.get("DestFolder").split("/")[row.get("DestFolder").split("/").length - 1];

        String insertSql = "INSERT into B2BCUSTOM.CIGNA_OB_CHANNEL_ADAPTERS (PRODUCER, CONSUMER, FILE_NAME, BP_RUN_SEQ, ADAPTER, PARM1, PARM2, PARM12, PARM13, PARM20, ECSR_NUMBER, BUCS_CODE, BUCS_IDENT) VALUES (?, ?, ?, 1, 'cigna.ch.ob.MBXDriver', ?, ?, ?, ?, 'P3', ?, ?, '113')";

        List<Object> data = Arrays.asList(row.get("AppUser"), row.get("Username"), row.get("FilenamePattern"), "/" + row.get("Username") + "/" + dFolder, fName, row.get("PGPKey"), row.get("PGPSign"), row.get("ECSR"));

        tryInsert(insertSql, data, this.connectionADC, this.snADC);
        tryInsert(insertSql, data, this.connectionWDC, this.snWDC);
    }

    public void obPushInsert(Map<String, String> row, String sftpProfile) {
        String destFolder = transformDestFolder(row.get("DestFolder"));

        String insertSql = "INSERT into B2BCUSTOM.CIGNA_OB_CHANNEL_ADAPTERS (PRODUCER, CONSUMER, FILE_NAME, BP_RUN_SEQ, ADAPTER, PARM1, PARM2, PARM3, PARM4, PARM9, PARM12, PARM13, PARM20, ECSR_NUMBER, BUCS_CODE, BUCS_IDENT) VALUES (?, ?, ?, 1, 'cigna.ch.ob.SFTPDriver', 'Cigna_SFTP_Client_External', ?, ?, ?, ?, ?, ?, 'P3', ?, ?, '113')";

        List<Object> data = Arrays.asList(row.get("AppUser"), row.get("Username"), row.get("FilenamePattern"), row.get("SFTPHost"), row.get("SFTPPort"), sftpProfile, destFolder, row.get("PGPKey"), row.get("PGPSign"), row.get("ECSR"), row.get("AppUser"));

        tryInsert(insertSql, data, this.connectionADC, this.snADC);
        tryInsert(insertSql, data, this.connectionWDC, this.snWDC);
    }


    public void runTest(String sftpProfile, String userType, String directory) {
        Properties config = this.configMgr.getConfig();
        try {
            // Load the XML file
            File xmlFile = new File(this.templatesDir + "/TEST_REMOTE_SFTP_PROFILE.xml");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(xmlFile);

            // Normalize the XML structure
            document.getDocumentElement().normalize();

            // Modify the XML elements
            modifyXmlElement(document, "ProfileName", sftpProfile);
            modifyXmlElement(document, "UserType", userType);
            modifyXmlElement(document, "Directory", directory);

            // Convert the modified document back to a string
            String xmlString = convertDocumentToString(document);

            // Prepare the URL and headers for the POST request
            String urlADC = config.getProperty("B2B.sftpSFTPTestURLADC");
            String urlWDC = config.getProperty("B2B.sftpSFTPTestURLWDC");
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/xml");

            // Send the POST request
            logEvent("info", "Testing SFTP connection in " + config.getProperty("OKTA.tmplSuffix") + " ADC");
            String responseADC = postXmlData(urlADC, xmlString, headers);
            logEvent("info", "Status code: 200");
            logEvent("info", "Response body: " + responseADC);

            logEvent("info", "Testing SFTP connection in " + config.getProperty("OKTA.tmplSuffix") + " WDC");
            String responseWDC = postXmlData(urlWDC, xmlString, headers);
            logEvent("info", "Status code: 200");
            logEvent("info", "Response body: " + responseWDC);

        } catch (Exception e) {
            throw new RuntimeException("Error occurred while running SFTP test: " + e.getMessage());
        }
    }

    private void modifyXmlElement(Document document, String tagName, String newValue) {
        NodeList nodeList = document.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            Element element = (Element) nodeList.item(0);
            element.setTextContent(newValue);
        }
    }

    private String convertDocumentToString(Document document) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource domSource = new DOMSource(document);
        StringWriter writer = new StringWriter();
        StreamResult streamResult = new StreamResult(writer);
        transformer.transform(domSource, streamResult);
        return writer.toString();
    }

    private void initOraDB() {
        Properties config = this.configMgr.getConfig();
        try {
            this.connectionADC = DriverManager.getConnection(config.getProperty("database.db_user_ADC"),
                    config.getProperty("database.db_pass_ADC"), this.dsnADC);
            logEvent("info", "Successfully connected to " + this.snADC + " Oracle DB");
        } catch (SQLException e) {
            logEvent("error", "Error connecting to " + this.snADC + " Oracle DB: " + e.getMessage());
            throw new RuntimeException("Error connecting to " + this.snADC + " Oracle DB: " + e.getMessage());
        }

        try {
            this.connectionWDC = DriverManager.getConnection(config.getProperty("database.db_user_WDC"),
                    config.getProperty("database.db_pass_WDC"), this.dsnWDC);
            logEvent("info", "Successfully connected to " + this.snWDC + " Oracle DB");
        } catch (SQLException e) {
            logEvent("error", "Error connecting to " + this.snWDC + " Oracle DB: " + e.getMessage());
            throw new RuntimeException("Error connecting to " + this.snWDC + " Oracle DB: " + e.getMessage());
        }
    }


}
