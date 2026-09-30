package com.moviebooking.common;

import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;

/** Binds a Java collection as a Postgres bigint[] parameter (used with unnest / = ANY). */
public final class SqlArrays {

    private SqlArrays() {
    }

    public static SqlTypeValue bigintArray(Collection<Long> values) {
        Long[] array = values.toArray(Long[]::new);
        return new AbstractSqlTypeValue() {
            @Override
            protected Array createTypeValue(Connection con, int sqlType, String typeName) throws SQLException {
                return con.createArrayOf("bigint", array);
            }
        };
    }
}
