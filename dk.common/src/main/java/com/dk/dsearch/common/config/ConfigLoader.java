package com.dk.dsearch.common.config;


import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;

public class ConfigLoader {

    public static AppConfig load(String yamlResourcePath) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(AppConfig.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);

        try (InputStream in = ConfigLoader.class
                .getClassLoader()
                .getResourceAsStream(yamlResourcePath)) {

            if (in == null) {
                throw new RuntimeException("Config file not found on classpath: " + yamlResourcePath);
            }
            return yaml.load(in);
        }
    }
}