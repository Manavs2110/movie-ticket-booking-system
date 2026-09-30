package com.moviebooking.service.catalog.internal;

import com.moviebooking.common.PageResponse;
import com.moviebooking.common.SqlArrays;
import com.moviebooking.config.AppProperties;
import com.moviebooking.config.CacheNames;
import com.moviebooking.dto.catalog.MovieSummary;
import com.moviebooking.dto.catalog.TheaterShowtimes;
import com.moviebooking.service.catalog.BrowseService;
import com.moviebooking.service.catalog.CityService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public browse queries (LLD §8). Indexed, city-scoped SQL; results are cached for a short time
 * because listings may be slightly stale (availability is always re-checked at hold time).
 */
@Service
public class BrowseServiceImpl implements BrowseService {

    private static final double KM_PER_DEGREE = 111.0;

    private final NamedParameterJdbcTemplate jdbc;
    private final CityService cityService;
    private final ZoneId zone;
    private final double defaultRadiusKm;

    public BrowseServiceImpl(NamedParameterJdbcTemplate jdbc, CityService cityService, AppProperties props) {
        this.jdbc = jdbc;
        this.cityService = cityService;
        this.zone = props.zoneId();
        this.defaultRadiusKm = props.geo().defaultRadiusKm();
    }

    /** §8.1 Movies showing in a city on a date, with optional language and genre filters. */
    @Cacheable(cacheNames = CacheNames.MOVIES_IN_CITY,
            key = "#cityId + ':' + #date + ':' + #language + ':' + #genre + ':' + #page + ':' + #size")
    @Transactional(readOnly = true)
    public PageResponse<MovieSummary> moviesInCity(Long cityId, LocalDate date, String language, String genre,
                                                   int page, int size) {
        cityService.requireExists(cityId);
        MapSqlParameterSource p = dayParams(date).addValue("cityId", cityId)
                .addValue("limit", size + 1).addValue("offset", page * size);
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.title, m.duration_minutes, m.language, m.genres, m.certificate, m.poster_url
                FROM movie m
                WHERE EXISTS (
                  SELECT 1 FROM show s
                  JOIN screen sc ON sc.id = s.screen_id
                  JOIN theater t ON t.id = sc.theater_id AND t.active
                  WHERE s.movie_id = m.id AND s.status = 'SCHEDULED' AND t.city_id = :cityId
                    AND s.start_time >= :dayStart AND s.start_time < :dayEnd AND s.start_time > now())
                """);
        if (language != null && !language.isBlank()) {
            sql.append(" AND lower(m.language) = lower(:language)");
            p.addValue("language", language.trim());
        }
        if (genre != null && !genre.isBlank()) {
            sql.append(" AND m.genres ILIKE '%' || :genre || '%'");
            p.addValue("genre", genre.trim());
        }
        sql.append(" ORDER BY m.title, m.id LIMIT :limit OFFSET :offset");

        List<MovieSummary> rows = jdbc.query(sql.toString(), p, (rs, i) -> MovieSummary.of(
                rs.getLong("id"), rs.getString("title"), rs.getInt("duration_minutes"), rs.getString("language"),
                rs.getString("genres"), rs.getString("certificate"), rs.getString("poster_url")));
        return PageResponse.offset(rows, page, size);
    }

    /**
     * §8.2 Theaters showing a movie in a city on a date, each with its showtimes.
     * With lat/lng: bounding-box filter, then sorted by great-circle distance ("near me"); not cached.
     */
    @Cacheable(cacheNames = CacheNames.SHOWS_FOR_MOVIE,
            key = "#movieId + ':' + #cityId + ':' + #date + ':' + #page + ':' + #size", condition = "#lat == null")
    @Transactional(readOnly = true)
    public PageResponse<TheaterShowtimes> showsForMovie(Long movieId, Long cityId, LocalDate date, Double lat,
                                                        Double lng, Double radiusKm, int page, int size) {
        cityService.requireExists(cityId);
        boolean geo = lat != null && lng != null;
        MapSqlParameterSource p = dayParams(date).addValue("cityId", cityId).addValue("movieId", movieId)
                .addValue("limit", size + 1).addValue("offset", page * size);

        StringBuilder sql = new StringBuilder("SELECT t.id, t.name, t.address, ");
        if (geo) {
            double radius = radiusKm != null ? radiusKm : defaultRadiusKm;
            double dLat = radius / KM_PER_DEGREE;
            double dLng = radius / (KM_PER_DEGREE * Math.max(0.01, Math.cos(Math.toRadians(lat))));
            p.addValue("lat", lat, Types.DOUBLE).addValue("lng", lng, Types.DOUBLE)
                    .addValue("dLat", dLat, Types.DOUBLE).addValue("dLng", dLng, Types.DOUBLE);
            sql.append("""
                    6371 * acos(least(1,
                        cos(radians(:lat)) * cos(radians(t.latitude)) * cos(radians(t.longitude) - radians(:lng))
                      + sin(radians(:lat)) * sin(radians(t.latitude)))) AS distance_km
                    """);
        } else {
            sql.append("CAST(NULL AS double precision) AS distance_km ");
        }
        sql.append("""
                FROM theater t
                WHERE t.city_id = :cityId AND t.active
                  AND EXISTS (SELECT 1 FROM show s JOIN screen sc ON sc.id = s.screen_id
                              WHERE sc.theater_id = t.id AND s.movie_id = :movieId AND s.status = 'SCHEDULED'
                                AND s.start_time >= :dayStart AND s.start_time < :dayEnd AND s.start_time > now())
                """);
        if (geo) {
            sql.append("""
                     AND t.latitude  BETWEEN :lat - :dLat AND :lat + :dLat
                     AND t.longitude BETWEEN :lng - :dLng AND :lng + :dLng
                    """);
        }
        sql.append(" ORDER BY distance_km NULLS LAST, t.name, t.id LIMIT :limit OFFSET :offset");

        record TheaterRow(Long id, String name, String address, Double distanceKm) {
        }
        List<TheaterRow> theaters = jdbc.query(sql.toString(), p, (rs, i) -> {
            double d = rs.getDouble("distance_km");
            return new TheaterRow(rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                    rs.wasNull() ? null : Math.round(d * 100) / 100.0);
        });
        if (theaters.isEmpty()) {
            return PageResponse.offset(List.of(), page, size);
        }

        // Second query: the showtimes of just the theaters on this page.
        MapSqlParameterSource sp = dayParams(date).addValue("movieId", movieId)
                .addValue("theaterIds", SqlArrays.bigintArray(theaters.stream().map(TheaterRow::id).toList()), Types.ARRAY);
        Map<Long, List<TheaterShowtimes.Showtime>> byTheater = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sc.theater_id, s.id, s.start_time, sc.name AS screen_name, s.regular_price, s.premium_price, s.weekend_multiplier
                FROM show s JOIN screen sc ON sc.id = s.screen_id
                WHERE sc.theater_id = ANY(:theaterIds) AND s.movie_id = :movieId AND s.status = 'SCHEDULED'
                  AND s.start_time >= :dayStart AND s.start_time < :dayEnd AND s.start_time > now()
                ORDER BY s.start_time, s.id
                """, sp, rs -> {
            byTheater.computeIfAbsent(rs.getLong("theater_id"), k -> new ArrayList<>())
                    .add(new TheaterShowtimes.Showtime(rs.getLong("id"), rs.getTimestamp("start_time").toInstant(),
                            rs.getString("screen_name"), rs.getBigDecimal("regular_price"),
                            rs.getBigDecimal("premium_price"), rs.getBigDecimal("weekend_multiplier")));
        });

        List<TheaterShowtimes> items = theaters.stream()
                .map(t -> new TheaterShowtimes(t.id(), t.name(), t.address(), t.distanceKm(),
                        byTheater.getOrDefault(t.id(), List.of())))
                .toList();
        return PageResponse.offset(items, page, size);
    }

    private MapSqlParameterSource dayParams(LocalDate date) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        return new MapSqlParameterSource()
                .addValue("dayStart", OffsetDateTime.ofInstant(dayStart, ZoneOffset.UTC))
                .addValue("dayEnd", OffsetDateTime.ofInstant(dayEnd, ZoneOffset.UTC));
    }
}
