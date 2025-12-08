package org.example.client;

import org.example.AppConfig;
import org.example.model.Flight;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SqliteReader {
    public List<Flight> readAll() {
        List<Flight> list = new ArrayList<>();
        String url = AppConfig.get().sqlitePath;

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM raw_flights")) {

            while (rs.next()) {
                list.add(new Flight(
                        rs.getString("flight_no"),
                        rs.getString("airline_name"),
                        rs.getString("aircraft_model"),
                        rs.getString("dep_city"),
                        rs.getString("dep_code"),
                        rs.getString("arr_city"),
                        rs.getString("arr_code"),
                        rs.getString("passenger_name"),
                        rs.getString("passport_no"),
                        rs.getString("flight_date")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
}
