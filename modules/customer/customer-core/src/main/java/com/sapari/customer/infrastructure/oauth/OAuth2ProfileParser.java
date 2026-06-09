package com.sapari.customer.infrastructure.oauth;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import com.sapari.user.model.UserGender;

public final class OAuth2ProfileParser {

    private OAuth2ProfileParser() {
    }

    public static String getString(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return null;
        }

        String digits = phoneNumber.replaceAll("\\D", "");

        if (digits.startsWith("82") && digits.length() > 2) {
            return "0" + digits.substring(2);
        }

        return digits.isBlank() ? null : digits;
    }

    public static UserGender parseGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }

        return switch (gender.toUpperCase(Locale.ROOT)) {
            case "M", "MALE" -> UserGender.MALE;
            case "F", "FEMALE" -> UserGender.FEMALE;
            default -> null;
        };
    }

    public static LocalDate parseBirthDate(String birthYear, String birthday) {
        if (birthYear == null || birthday == null || birthYear.isBlank() || birthday.isBlank()) {
            return null;
        }

        String year = birthYear.replaceAll("\\D", "");
        String day = birthday.replaceAll("\\D", "");

        if (year.length() != 4 || day.length() != 4) {
            return null;
        }

        try {
            return LocalDate.of(
                    Integer.parseInt(year),
                    Integer.parseInt(day.substring(0, 2)),
                    Integer.parseInt(day.substring(2, 4))
            );
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}
