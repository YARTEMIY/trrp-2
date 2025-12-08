package org.example.model;

import java.io.Serializable;

public record Flight(
        String flightNo,
        String airlineName,
        String aircraftModel,
        String depCity,
        String depCode,
        String arrCity,
        String arrCode,
        String passengerName,
        String passportNo,
        String flightDate
) implements Serializable {}
