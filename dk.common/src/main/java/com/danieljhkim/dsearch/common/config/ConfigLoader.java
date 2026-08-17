package com.danieljhkim.dsearch.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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
            return applyRuntimeNodeCounts(
                    yaml.load(in), System.getenv("N_INDEX_NODES"), System.getenv("N_QUERY_NODES"));
        }
    }

    static AppConfig applyRuntimeNodeCounts(AppConfig config, String indexNodeCount, String queryNodeCount) {
        applyRuntimeNodeCount(config.getIndexNodes(), indexNodeCount, "N_INDEX_NODES");
        applyRuntimeNodeCount(config.getQueryNodes(), queryNodeCount, "N_QUERY_NODES");
        return config;
    }

    private static void applyRuntimeNodeCount(
            AppConfig.NodeGroupConfig groupConfig, String nodeCountValue, String environmentVariable) {
        if (nodeCountValue == null || nodeCountValue.isBlank()) {
            return;
        }
        if (groupConfig == null || groupConfig.getNodes() == null) {
            throw new IllegalArgumentException(
                    environmentVariable + " is set but its configured node group is missing");
        }

        int nodeCount;
        try {
            nodeCount = Integer.parseInt(nodeCountValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    environmentVariable + " must be a positive integer: " + nodeCountValue, e);
        }
        if (nodeCount < 1) {
            throw new IllegalArgumentException(environmentVariable + " must be a positive integer: " + nodeCountValue);
        }
        List<AppConfig.NodeConfig> configuredNodes = groupConfig.getNodes();
        if (nodeCount > configuredNodes.size()) {
            throw new IllegalArgumentException(environmentVariable + " requests " + nodeCount + " nodes, but only "
                    + configuredNodes.size() + " are configured");
        }
        groupConfig.setNodes(List.copyOf(configuredNodes.subList(0, nodeCount)));
    }
}
