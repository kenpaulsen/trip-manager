package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import lombok.Value;
import org.paulsens.trip.util.RandomData;

@Value
public class DataId implements Serializable, Comparable<DataId> {
    @JsonValue
    String value;

    public static DataId from(final String id) {
        return new DataId(id);
    }

    public static DataId newInstance() {
        return new DataId(RandomData.genString(18, RandomData.ALPHA_NUM));
    }

    @Override
    public int compareTo(DataId o) {
        return value.compareTo(o.getValue());
    }
}
