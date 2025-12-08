package org.example;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class AppConfig {
    public final String transportMode;
    public final String sqlitePath;
    public final String pgUrl;
    public final String pgUser;
    public final String pgPass;
    public final String socketHost;
    public final int socketPort;
    public final String rmqHost;
    public final String rmqQueue;
    public final String rmqUser;
    public final String rmqPass;

    private static AppConfig INSTANCE;

    private AppConfig() {
        Config cfg = ConfigFactory.load().getConfig("app");

        this.transportMode = cfg.getString("transportMode");
        this.sqlitePath = cfg.getString("database.sqlitePath");
        this.pgUrl = cfg.getString("database.postgresUrl");
        this.pgUser = cfg.getString("database.postgresUser");
        this.pgPass = cfg.getString("database.postgresPass");
        this.socketHost = cfg.getString("socketServer.host");
        this.socketPort = cfg.getInt("socketServer.port");
        this.rmqHost = cfg.getString("rabbitMq.host");
        this.rmqQueue = cfg.getString("rabbitMq.queueName");
        this.rmqUser = cfg.getString("rabbitMq.username");
        this.rmqPass = cfg.getString("rabbitMq.password");
    }

    public static synchronized AppConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new AppConfig();
        }
        return INSTANCE;
    }
}
