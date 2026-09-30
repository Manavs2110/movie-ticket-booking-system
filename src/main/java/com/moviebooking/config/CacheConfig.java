package com.moviebooking.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.moviebooking.common.PageResponse;
import com.moviebooking.dto.catalog.CityResponse;
import com.moviebooking.dto.catalog.MovieResponse;
import com.moviebooking.dto.catalog.MovieSummary;
import com.moviebooking.dto.catalog.TheaterShowtimes;
import com.moviebooking.model.catalog.SeatLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Shared Redis cache for browse data (HLD §10.3).
 * Every cache holds exactly one value type, so each gets a typed JSON serializer: no class names
 * stored in Redis, and values stay readable in RedisInsight.
 * Redis errors are logged and swallowed, so an outage falls back to Postgres.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCaches(ObjectMapper objectMapper, AppProperties props) {
        TypeFactory tf = objectMapper.getTypeFactory();
        AppProperties.CacheTtlProps ttl = props.cacheTtl();
        Map<String, CacheSpec> specs = Map.of(
                CacheNames.CITIES, new CacheSpec(tf.constructCollectionType(List.class, CityResponse.class), ttl.cities()),
                CacheNames.MOVIE, new CacheSpec(tf.constructType(MovieResponse.class), ttl.movie()),
                CacheNames.MOVIES_IN_CITY, new CacheSpec(
                        tf.constructParametricType(PageResponse.class, MovieSummary.class), ttl.moviesInCity()),
                CacheNames.SHOWS_FOR_MOVIE, new CacheSpec(
                        tf.constructParametricType(PageResponse.class, TheaterShowtimes.class), ttl.showsForMovie()),
                CacheNames.SEAT_LAYOUT, new CacheSpec(tf.constructType(SeatLayout.class), ttl.seatLayout()));

        return builder -> specs.forEach((name, spec) -> builder.withCacheConfiguration(name,
                RedisCacheConfiguration.defaultCacheConfig()
                        .computePrefixWith(CacheKeyPrefix.prefixed("mtbs::"))
                        .entryTtl(spec.ttl())
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper, spec.type())))));
    }

    private record CacheSpec(JavaType type, Duration ttl) {
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache get failed [{}:{}], falling back to database: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache put failed [{}:{}]: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache evict failed [{}:{}]: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Cache clear failed [{}]: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
