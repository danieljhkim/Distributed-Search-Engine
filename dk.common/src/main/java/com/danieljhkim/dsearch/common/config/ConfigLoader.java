package com.danieljhkim.dsearch.common.config;

import java.io.IOException;
import java.io.InputStream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class ConfigLoader {

    /**
     * Gets the config file path from APP_CONFIG_PATH environment variable,
     * defaulting to "app-config.yaml" if not set.
     */
    public static String getConfigFilePath() {
        String appConfigPath = System.getenv("APP_CONFIG_PATH");
        if (appConfigPath == null || appConfigPath.isEmpty()) {
            appConfigPath = "app-config.yaml";
        }
        return appConfigPath;
    }

    /**
     * Loads the application configuration using the path from APP_CONFIG_PATH
     * environment variable, defaulting to "app-config.yaml" if not set.
     */
    public static AppConfig load() throws IOException {
        return load(getConfigFilePath());
    }

    /**
     * Loads the application configuration from the specified resource path.
     */
    public static AppConfig load(String yamlResourcePath) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(AppConfig.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);

        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(yamlResourcePath)) {

            if (in == null) {
                throw new RuntimeException("Config file not found on classpath: " + yamlResourcePath);
            }
            return yaml.load(in);
        }
    }
}
