package org.example.server;

import org.example.AppConfig;
import org.example.model.Flight;

import java.sql.*;

public class PostgresService {
    private final Connection conn;

    public PostgresService() throws SQLException {
        AppConfig cfg = AppConfig.get();
        this.conn = DriverManager.getConnection(cfg.pgUrl, cfg.pgUser, cfg.pgPass);
    }

    public void saveFlight(Flight dto) throws SQLException {
        conn.setAutoCommit(false); // Транзакция
        try {
            // 1. Нормализация справочников (находим ID или создаем новый)
            int airlineId = getOrInsert(conn, "airlines", "name", dto.airlineName());
            int aircraftId = getOrInsert(conn, "aircrafts", "model", dto.aircraftModel());

            insertAirport(conn, dto.depCode(), dto.depCity());
            insertAirport(conn, dto.arrCode(), dto.arrCity());

            int passengerId = getOrInsertPassenger(conn, dto.passportNo(), dto.passengerName());

            // 2. Вставка самого полета
            String sql = "INSERT INTO flights (flight_no, date, airline_id, aircraft_id, dep_airport_code, arr_airport_code, passenger_id) VALUES (?, ?::timestamp, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, dto.flightNo());
                ps.setString(2, dto.flightDate() + " 00:00:00");
                ps.setInt(3, airlineId);
                ps.setInt(4, aircraftId);
                ps.setString(5, dto.depCode());
                ps.setString(6, dto.arrCode());
                ps.setInt(7, passengerId);
                ps.executeUpdate();
            }
            conn.commit();
            System.out.println("Saved flight: " + dto.flightNo() + " (" + dto.passengerName() + ")");
        } catch (Exception e) {
            conn.rollback();
            System.err.println("Error saving flight: " + e.getMessage());
            e.printStackTrace(); // Полезно для отладки SQL
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // Хелпер: Найти ID или Вставить и вернуть ID
    private int getOrInsert(Connection c, String table, String col, String val) throws SQLException {
        String select = "SELECT id FROM " + table + " WHERE " + col + " = ?";
        try (PreparedStatement ps = c.prepareStatement(select)) {
            ps.setString(1, val);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        }
        String insert = "INSERT INTO " + table + " (" + col + ") VALUES (?) RETURNING id";
        try (PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setString(1, val);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private void insertAirport(Connection c, String code, String city) throws SQLException {
        String sql = "INSERT INTO airports (code, city) VALUES (?, ?) ON CONFLICT (code) DO NOTHING";
        try(PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, city);
            ps.executeUpdate();
        }
    }

    private int getOrInsertPassenger(Connection c, String passport, String name) throws SQLException {
        String select = "SELECT id FROM passengers WHERE passport_no = ?";
        try (PreparedStatement ps = c.prepareStatement(select)) {
            ps.setString(1, passport);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        }
        String insert = "INSERT INTO passengers (passport_no, full_name) VALUES (?, ?) RETURNING id";
        try (PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setString(1, passport);
            ps.setString(2, name);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }
}
