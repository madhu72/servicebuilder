package com.mftlabs.autobuild;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class AutoBuildConfig {

    private String configFile;
    private Properties config;

    private static AutoBuildConfig instance;


    public static AutoBuildConfig getInstance(String configFile) {
        if (instance == null) {
            instance = new AutoBuildConfig(configFile);
            instance.getConfig();
        }
        return instance;
    }

    public AutoBuildConfig(String configFile) {
        this.configFile = configFile;
    }

    public Properties getConfig() {
        config = new Properties();
        try {
            config.load(new FileInputStream(configFile));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return config;
    }
}
