package org.paulsens.trip.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.paulsens.trip.dynamo.DAO;

@Named("json")
@ApplicationScoped
public class JsonCommands {
    public String toJson(final Object obj) {
        try {
            return DAO.getInstance().getMapper().writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            return ex.getMessage();
        }
    }
}
