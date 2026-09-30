package com.moviebooking.model.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "movie")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    private String language;

    /** Comma-separated, e.g. "Action,Sci-Fi". */
    @Column(nullable = false)
    private String genres;

    @Column(nullable = false)
    private String certificate;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @Column(name = "cast_members", columnDefinition = "text")
    private String castMembers;

    @Column(name = "poster_url")
    private String posterUrl;

    @Column(name = "trailer_url")
    private String trailerUrl;

    protected Movie() {
    }

    public Movie(String title) {
        this.title = title;
    }

    public void update(String title, String description, int durationMinutes, String language, String genres,
                       String certificate, LocalDate releaseDate, String castMembers, String posterUrl,
                       String trailerUrl) {
        this.title = title;
        this.description = description;
        this.durationMinutes = durationMinutes;
        this.language = language;
        this.genres = genres;
        this.certificate = certificate;
        this.releaseDate = releaseDate;
        this.castMembers = castMembers;
        this.posterUrl = posterUrl;
        this.trailerUrl = trailerUrl;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getLanguage() {
        return language;
    }

    public String getGenres() {
        return genres;
    }

    public String getCertificate() {
        return certificate;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public String getCastMembers() {
        return castMembers;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public String getTrailerUrl() {
        return trailerUrl;
    }
}
